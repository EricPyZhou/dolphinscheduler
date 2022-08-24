/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.dolphinscheduler.common.utils;

import static org.apache.dolphinscheduler.common.Constants.AWS_END_POINT;
import static org.apache.dolphinscheduler.common.Constants.FOLDER_SEPARATOR;
import static org.apache.dolphinscheduler.common.Constants.FORMAT_S_S;
import static org.apache.dolphinscheduler.common.Constants.RESOURCE_STORAGE_TYPE;
import static org.apache.dolphinscheduler.common.Constants.RESOURCE_TYPE_FILE;
import static org.apache.dolphinscheduler.common.Constants.RESOURCE_TYPE_UDF;
import static org.apache.dolphinscheduler.common.Constants.STORAGE_S3;

import org.apache.dolphinscheduler.common.Constants;
import org.apache.dolphinscheduler.common.enums.ResUploadType;
import org.apache.dolphinscheduler.common.storage.StorageEntity;
import org.apache.dolphinscheduler.common.storage.StorageOperate;
import org.apache.dolphinscheduler.plugin.task.api.TaskConstants;
import org.apache.dolphinscheduler.spi.enums.ResourceType;

import org.apache.commons.lang3.StringUtils;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.amazonaws.AmazonServiceException;
import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.client.builder.AwsClientBuilder;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.s3.model.AmazonS3Exception;
import com.amazonaws.services.s3.model.Bucket;
import com.amazonaws.services.s3.model.ObjectMetadata;
import com.amazonaws.services.s3.model.PutObjectRequest;
import com.amazonaws.services.s3.model.S3Object;
import com.amazonaws.services.s3.model.S3ObjectInputStream;
import com.amazonaws.services.s3.transfer.MultipleFileDownload;
import com.amazonaws.services.s3.transfer.TransferManager;
import com.amazonaws.services.s3.transfer.TransferManagerBuilder;

public class S3Utils implements Closeable, StorageOperate {

    private static final Logger logger = LoggerFactory.getLogger(S3Utils.class);

    public static final String ACCESS_KEY_ID = PropertyUtils.getString(TaskConstants.AWS_ACCESS_KEY_ID);

    public static final String SECRET_KEY_ID = PropertyUtils.getString(TaskConstants.AWS_SECRET_ACCESS_KEY);

    public static final String REGION = PropertyUtils.getString(TaskConstants.AWS_REGION);

    public static final String BUCKET_NAME = PropertyUtils.getString(Constants.AWS_S3_BUCKET_NAME);


    private AmazonS3 s3Client = null;

    private S3Utils() {
        if (PropertyUtils.getString(RESOURCE_STORAGE_TYPE).equals(STORAGE_S3)) {

            if (!StringUtils.isEmpty(PropertyUtils.getString(AWS_END_POINT))) {
                s3Client = AmazonS3ClientBuilder
                    .standard()
                    .withPathStyleAccessEnabled(true)
                    .withEndpointConfiguration(new AwsClientBuilder.EndpointConfiguration(PropertyUtils.getString(AWS_END_POINT), Regions.fromName(REGION).getName()))
                    .withCredentials(new AWSStaticCredentialsProvider(new BasicAWSCredentials(ACCESS_KEY_ID, SECRET_KEY_ID)))
                    .build();
            } else {
                s3Client = AmazonS3ClientBuilder
                    .standard()
                    .withCredentials(new AWSStaticCredentialsProvider(new BasicAWSCredentials(ACCESS_KEY_ID, SECRET_KEY_ID)))
                    .withRegion(Regions.fromName(REGION))
                    .build();
            }
            checkBucketNameExists(BUCKET_NAME);
        }
    }

    /**
     * S3Utils single
     */
    private enum S3Singleton {
        INSTANCE;

        private final S3Utils instance;

        S3Singleton() {
            instance = new S3Utils();
        }

        private S3Utils getInstance() {
            return instance;
        }
    }

    public static S3Utils getInstance() {
        return S3Singleton.INSTANCE.getInstance();
    }

    @Override
    public void close() throws IOException {
        s3Client.shutdown();
    }

    @Override
    public void createTenantDirIfNotExists(String tenantCode) throws Exception {
        getInstance().mkdir(tenantCode, getS3ResDir(tenantCode));
        getInstance().mkdir(tenantCode, getS3UdfDir(tenantCode));
    }

    @Override
    public String getResDir(String tenantCode) {
        return getS3ResDir(tenantCode) + FOLDER_SEPARATOR;
    }

    @Override
    public String getUdfDir(String tenantCode) {
        return getS3UdfDir(tenantCode) + FOLDER_SEPARATOR;
    }

    @Override
    public boolean mkdir(String tenantCode, String path) throws IOException {
        String objectName = path + FOLDER_SEPARATOR;
        if (!s3Client.doesObjectExist(BUCKET_NAME, objectName)) {
            ObjectMetadata metadata = new ObjectMetadata();
            metadata.setContentLength(0);
            InputStream emptyContent = new ByteArrayInputStream(new byte[0]);
            PutObjectRequest putObjectRequest = new PutObjectRequest(BUCKET_NAME, objectName, emptyContent, metadata);
            s3Client.putObject(putObjectRequest);
        }
        return true;
    }

    @Override
    public String getResourceFileName(String tenantCode, String fileName) {
        if (fileName.startsWith(FOLDER_SEPARATOR)) {
            fileName = fileName.replaceFirst(FOLDER_SEPARATOR, "");
        }
        return String.format(FORMAT_S_S, getS3ResDir(tenantCode), fileName);
    }

    @Override
    public String getFileName(ResourceType resourceType, String tenantCode, String fileName) {
        if (fileName.startsWith(FOLDER_SEPARATOR)) {
            fileName = fileName.replaceFirst(FOLDER_SEPARATOR, "");
        }
        return getDir(resourceType, tenantCode) + fileName;
    }

    @Override
    public void download(String tenantCode, String srcFilePath, String dstFilePath, boolean deleteSource, boolean overwrite) throws IOException {
        File dstFile = new File(dstFilePath);
        if (dstFile.isDirectory()) {
            Files.delete(dstFile.toPath());
        } else {
            Files.createDirectories(dstFile.getParentFile().toPath());
        }
        S3Object o = s3Client.getObject(BUCKET_NAME, srcFilePath);
        try (S3ObjectInputStream s3is = o.getObjectContent();
             FileOutputStream fos = new FileOutputStream(dstFilePath)) {
            byte[] readBuf = new byte[1024];
            int readLen;
            while ((readLen = s3is.read(readBuf)) > 0) {
                fos.write(readBuf, 0, readLen);
            }
        } catch (AmazonServiceException e) {
            throw new IOException(e.getMessage());
        } catch (FileNotFoundException e) {
            logger.error("the destination file {} not found", dstFilePath);
            throw e;
        }
    }

    @Override
    public boolean exists(String fullName) throws IOException{
        return s3Client.doesObjectExist(BUCKET_NAME, fullName);
    }

    @Override
    public boolean delete(String tenantCode, String filePath, boolean recursive) throws IOException {
        try {
            s3Client.deleteObject(BUCKET_NAME, filePath);
            return true;
        } catch (AmazonServiceException e) {
            logger.error("delete the object error,the resource path is {}", filePath);
            return false;
        }
    }

    @Override
    public boolean delete(String tenantCode, String[] filePathArray, boolean recursive) throws IOException {
        DeleteObjectsRequest deleteObjectsRequest = new DeleteObjectsRequest(BUCKET_NAME)
                .withKeys(filePathArray);
        try {
            s3Client.deleteObjects(deleteObjectsRequest);
        }  catch (AmazonServiceException e) {
            System.out.println(e.getMessage());
            return false;
        }

        return true;
    }

    @Override
    public boolean copy(String srcPath, String dstPath, boolean deleteSource, boolean overwrite) throws IOException {
        s3Client.copyObject(BUCKET_NAME, srcPath, BUCKET_NAME, dstPath);
        s3Client.deleteObject(BUCKET_NAME, srcPath);
        return true;
    }

    @Override
    public String getDir(ResourceType resourceType, String tenantCode) {
        switch (resourceType) {
            case UDF:
                return getUdfDir(tenantCode);
            case FILE:
                return getResDir(tenantCode);
            default:
                return "";
        }

    }

    @Override
    public boolean upload(String tenantCode, String srcFile, String dstPath, boolean deleteSource, boolean overwrite) throws IOException {
        try {
            s3Client.putObject(BUCKET_NAME, dstPath, new File(srcFile));
            return true;
        } catch (AmazonServiceException e) {
            logger.error("upload failed,the bucketName is {},the filePath is {}", BUCKET_NAME, dstPath);
            return false;
        }
    }

    @Override
    public List<String> vimFile(String tenantCode, String filePath, int skipLineNums, int limit) throws IOException {
        if (StringUtils.isBlank(filePath)) {
            logger.error("file path:{} is blank", filePath);
            return Collections.emptyList();
        }
        S3Object s3Object = s3Client.getObject(BUCKET_NAME, filePath);
        try (BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(s3Object.getObjectContent()))) {
            Stream<String> stream = bufferedReader.lines().skip(skipLineNums).limit(limit);
            return stream.collect(Collectors.toList());
        }
    }

    @Override
    public void deleteTenant(String tenantCode) throws Exception {
        deleteTenantCode(tenantCode);
    }

    /**
     * S3 resource dir
     *
     * @param tenantCode tenant code
     * @return S3 resource dir
     */
    public static String getS3ResDir(String tenantCode) {
        return String.format("%s/" + RESOURCE_TYPE_FILE, getS3TenantDir(tenantCode));
    }

    /**
     * S3 udf dir
     *
     * @param tenantCode tenant code
     * @return get udf dir on S3
     */
    public static String getS3UdfDir(String tenantCode) {
        return String.format("%s/" + RESOURCE_TYPE_UDF, getS3TenantDir(tenantCode));
    }

    /**
     * @param tenantCode tenant code
     * @return file directory of tenants on S3
     */
    public static String getS3TenantDir(String tenantCode) {
        return String.format(FORMAT_S_S, getS3DataBasePath(), tenantCode);
    }

    /**
     * get data S3 path
     *
     * @return data S3 path
     */
    public static String getS3DataBasePath() {
        if (FOLDER_SEPARATOR.equals(RESOURCE_UPLOAD_PATH)) {
            return "";
        } else {
            return RESOURCE_UPLOAD_PATH.replaceFirst(FOLDER_SEPARATOR, "");
        }
    }

    private void deleteTenantCode(String tenantCode) {
        deleteDirectory(getResDir(tenantCode));
        deleteDirectory(getUdfDir(tenantCode));
    }

    /**
     * xxx   untest
     * upload local directory to S3
     *
     * @param tenantCode
     * @param keyPrefix the name of directory
     * @param strPath
     */
    private void uploadDirectory(String tenantCode, String keyPrefix, String strPath) {
        s3Client.putObject(BUCKET_NAME, tenantCode + FOLDER_SEPARATOR + keyPrefix, new File(strPath));
    }


    /**
     * xxx untest
     * download S3 Directory to local
     *
     * @param tenantCode
     * @param keyPrefix the name of directory
     * @param srcPath
     */
    private void downloadDirectory(String tenantCode, String keyPrefix, String srcPath) {
        TransferManager tm = TransferManagerBuilder.standard().withS3Client(s3Client).build();
        try {
            MultipleFileDownload download = tm.downloadDirectory(BUCKET_NAME, tenantCode + FOLDER_SEPARATOR + keyPrefix, new File(srcPath));
            download.waitForCompletion();
        } catch (AmazonS3Exception | InterruptedException e) {
            logger.error("download the directory failed with the bucketName is {} and the keyPrefix is {}", BUCKET_NAME, tenantCode + FOLDER_SEPARATOR + keyPrefix);
            Thread.currentThread().interrupt();
        } finally {
            tm.shutdownNow();
        }
    }

    public void checkBucketNameExists(String bucketName) {
        if (StringUtils.isBlank(bucketName)) {
            throw new IllegalArgumentException("resource.aws.s3.bucket.name is blank");
        }

        Bucket existsBucket = s3Client.listBuckets()
            .stream()
            .filter(
                bucket -> bucket.getName().equals(bucketName)
            )
            .findFirst()
            .orElseThrow(() -> {
                return new IllegalArgumentException("bucketName: " + bucketName + " is not exists, you need to create them by yourself");
            });

        logger.info("bucketName: {} has been found, the current regionName is {}", existsBucket.getName(), s3Client.getRegionName());
    }

    /**
     * only delete the object of directory ,it`s better to delete the files in it -r
     */
    private void deleteDirectory(String directoryName) {
        if (s3Client.doesObjectExist(BUCKET_NAME, directoryName)) {
            s3Client.deleteObject(BUCKET_NAME, directoryName);
        }
    }

    @Override
    public ResUploadType returnStorageType() {
        return ResUploadType.S3;
    }


    @Override
    public List<String> listAllChildrenPaths(String path) {
        List<String> childrenPaths = new ArrayList<>();

        ListObjectsV2Request request = new ListObjectsV2Request();
        request.setBucketName(BUCKET_NAME);
        request.setPrefix(path);
        ListObjectsV2Result v2Result;
        do {
            try {
                v2Result = s3Client.listObjectsV2(request);
            } catch (AmazonServiceException e) {
                logger.error("Get S3 file list exception", e);
                throw new AmazonServiceException("Get S3 file list exception", e);
            }

            List<S3ObjectSummary> summaries = v2Result.getObjectSummaries();
            for (S3ObjectSummary summary: summaries) {
                childrenPaths.add(summary.getKey());
            }

            request.setContinuationToken(v2Result.getContinuationToken());
        } while (v2Result.isTruncated());

        return childrenPaths;
    }

    @Override
    public List<StorageEntity> listFilesStatusRecursively (String path, String defaultPath, String tenantCode, ResourceType type) {
        // TODO: consider truncate
        List<StorageEntity> storageEntityList = new ArrayList<>();

        LinkedList<StorageEntity> foldersToFetch = new LinkedList<>();

        do {
            List<StorageEntity> tempList = new ArrayList<>();
            try{
                if (foldersToFetch.size() == 0) {
                    tempList = listFilesStatus(path, defaultPath, tenantCode, type);
                } else {
                    StorageEntity folderToExplore = foldersToFetch.pop();
                    tempList = listFilesStatus(folderToExplore.getFullName(), defaultPath, tenantCode, type);
                }
            } catch (AmazonServiceException e) {
                logger.error("Get S3 file list exception recursively", e);
                return new ArrayList<StorageEntity>();
            }

            for (StorageEntity temp : tempList) {
                if (temp.isDirectory()) {
                    foldersToFetch.add(temp);
                }
            }

            storageEntityList.addAll(tempList);

        } while (foldersToFetch.size() != 0);

        return storageEntityList;

    }

    @Override
    public List<StorageEntity> listFilesStatus(String path, String defaultPath, String tenantCode, ResourceType type) throws AmazonServiceException {
        List<StorageEntity> storageEntityList = new ArrayList<>();

        //TODO: optimize pagination
        ListObjectsV2Request request = new ListObjectsV2Request();
        request.setBucketName(BUCKET_NAME);
        request.setPrefix(path);
//        request.setMaxKeys(4);
//        request.setStartAfter("dolphinscheduler-test/tenant1/ds/resources/Sneww3.png");
        request.setDelimiter("/");

        ListObjectsV2Result v2Result;
        do {
            try {
                v2Result = s3Client.listObjectsV2(request);
            } catch (AmazonServiceException e) {
                logger.error("Get S3 file list exception", e);
                throw new AmazonServiceException("Get S3 file list exception", e);
            }

            List<S3ObjectSummary> summaries = v2Result.getObjectSummaries();

            // file.getKey().endsWith("/") is a folder
            for (S3ObjectSummary summary: summaries) {
                if (!summary.getKey().endsWith("/")) {
                    String[] aliasArr = summary.getKey().split("/");
                    String alias = aliasArr[aliasArr.length - 1];

                    String fileName = StringUtils.difference(defaultPath, summary.getKey());
                    storageEntityList.add(new StorageEntity.Builder()
                            .alias(alias)
                            .fileName(fileName)
                            .fullName(summary.getKey())
                            .isDirectory(false)
                            .description("")
//                        .userId(userId)
                            .userName(tenantCode)
                            .type(ResourceType.FILE)
                            .size(summary.getSize())
                            .createTime(summary.getLastModified())
                            .updateTime(summary.getLastModified())
                            .pfullName(path)
                            .build());
                }
            }

            for (String commonPrefix: v2Result.getCommonPrefixes()) {
                String suffix = StringUtils.difference(path, commonPrefix);
                String fileName = StringUtils.difference(defaultPath, commonPrefix);

                storageEntityList.add(new StorageEntity.Builder()
                        .alias(suffix)
                        .fileName(fileName)
                        .fullName(commonPrefix)
                        .isDirectory(true)
                        .description("")
//                    .userId(userId)
                        .userName(tenantCode)
                        .type(type)
                        .size(0)
                        .createTime(null)
                        .updateTime(null)
                        .pfullName(path)
                        .build());
            }

            request.setContinuationToken(v2Result.getContinuationToken());

        } while (v2Result.isTruncated());

        return storageEntityList;
    }

    @Override
    public StorageEntity getFileStatus(String path, String defaultPath, String tenantCode, ResourceType type) throws AmazonServiceException{
        // TODO: udf type
        ListObjectsV2Request request = new ListObjectsV2Request();
        request.setBucketName(BUCKET_NAME);
        request.setPrefix(path);
        request.setDelimiter("/");

        ListObjectsV2Result v2Result;
        try {
            v2Result = s3Client.listObjectsV2(request);
        } catch (AmazonServiceException e) {
            logger.error("Get S3 file list exception", e);
            throw new AmazonServiceException("Get S3 file list exception", e);
        }

        List<S3ObjectSummary> summaries = v2Result.getObjectSummaries();

        if (path.endsWith("/")) {
            String alias = findDirAlias(path);
            String fileName = StringUtils.difference(defaultPath, path);
            return new StorageEntity.Builder()
                    .alias(alias)
                    .fileName(fileName)
                    .fullName(path)
                    .isDirectory(true)
                    .description("")
//                    .userId(2)
                    .userName(tenantCode)
                    .type(type)
                    .size(0)
                    .build();
        } else {
            if (summaries.size() > 0) {
                S3ObjectSummary summary = summaries.get(0);
                String[] aliasArr = summary.getKey().split("/");
                String alias = aliasArr[aliasArr.length - 1];

                String fileName = StringUtils.difference(defaultPath, summary.getKey());
                return new StorageEntity.Builder()
                        .alias(alias)
                        .fileName(fileName)
                        .fullName(summary.getKey())
                        .isDirectory(false)
                        .description("")
//                        .userId(2)
                        .userName(tenantCode)
                        .type(type)
                        .size(summary.getSize())
                        .createTime(summary.getLastModified())
                        .updateTime(summary.getLastModified())
                        .build();
            }
        }

        return null;
    }

    /**
     * find alias for directories, NOT for files
     * a directory is a path ending with "/"
     */
    // Later will be moved to storageOperate class so that HDFSUtil can use it
    private String findDirAlias(String myStr) {
        if (!myStr.endsWith("/")) {
            // Make sure system won't crush down if someone accidentally misuse the function.
            return myStr;
        }
        int lastIndex = myStr.lastIndexOf("/");
        String subedString = myStr.substring(0, lastIndex);
        int secondLastIndex = subedString.lastIndexOf("/");
        StringBuilder stringBuilder = new StringBuilder();
        stringBuilder.append(myStr, secondLastIndex + 1, lastIndex + 1);

        return stringBuilder.toString();
    }
}
