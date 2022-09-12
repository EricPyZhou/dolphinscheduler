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

package org.apache.dolphinscheduler.api.service.impl;

import static org.apache.dolphinscheduler.common.Constants.ALIAS;
import static org.apache.dolphinscheduler.common.Constants.CONTENT;
import static org.apache.dolphinscheduler.common.Constants.EMPTY_STRING;
import static org.apache.dolphinscheduler.common.Constants.FOLDER_SEPARATOR;
import static org.apache.dolphinscheduler.common.Constants.FORMAT_SS;
import static org.apache.dolphinscheduler.common.Constants.FORMAT_S_S;
import static org.apache.dolphinscheduler.common.Constants.JAR;
import static org.apache.dolphinscheduler.common.Constants.PERIOD;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.apache.dolphinscheduler.api.constants.ApiFuncIdentificationConstant;
import org.apache.dolphinscheduler.api.dto.resources.ResourceComponent;
import org.apache.dolphinscheduler.api.dto.resources.filter.ResourceFilter;
import org.apache.dolphinscheduler.api.dto.resources.visitor.ResourceTreeVisitor;
import org.apache.dolphinscheduler.api.dto.resources.visitor.Visitor;
import org.apache.dolphinscheduler.api.enums.Status;
import org.apache.dolphinscheduler.api.exceptions.ServiceException;
import org.apache.dolphinscheduler.api.service.ResourcesService;
import org.apache.dolphinscheduler.api.utils.PageInfo;
import org.apache.dolphinscheduler.api.utils.RegexUtils;
import org.apache.dolphinscheduler.api.utils.Result;
import org.apache.dolphinscheduler.common.Constants;
import org.apache.dolphinscheduler.common.enums.AuthorizationType;
import org.apache.dolphinscheduler.common.enums.ProgramType;
import org.apache.dolphinscheduler.common.enums.ResUploadType;
import org.apache.dolphinscheduler.common.storage.StorageEntity;
import org.apache.dolphinscheduler.common.storage.StorageOperate;
import org.apache.dolphinscheduler.common.utils.FileUtils;
import org.apache.dolphinscheduler.common.utils.JSONUtils;
import org.apache.dolphinscheduler.common.utils.PropertyUtils;
import org.apache.dolphinscheduler.dao.entity.Resource;
import org.apache.dolphinscheduler.dao.entity.ResourcesTask;
import org.apache.dolphinscheduler.dao.entity.TaskDefinition;
import org.apache.dolphinscheduler.dao.entity.Tenant;
import org.apache.dolphinscheduler.dao.entity.UdfFunc;
import org.apache.dolphinscheduler.dao.entity.User;
import org.apache.dolphinscheduler.dao.mapper.ProcessDefinitionMapper;
import org.apache.dolphinscheduler.dao.mapper.ResourceMapper;
import org.apache.dolphinscheduler.dao.mapper.ResourceTaskMapper;
import org.apache.dolphinscheduler.dao.mapper.ResourceUserMapper;
import org.apache.dolphinscheduler.dao.mapper.TaskDefinitionMapper;
import org.apache.dolphinscheduler.dao.mapper.TenantMapper;
import org.apache.dolphinscheduler.dao.mapper.UdfFuncMapper;
import org.apache.dolphinscheduler.dao.mapper.UserMapper;
import org.apache.dolphinscheduler.dao.utils.ResourceProcessDefinitionUtils;
import org.apache.dolphinscheduler.plugin.task.api.model.ResourceInfo;
import org.apache.dolphinscheduler.spi.enums.ResourceType;

import org.apache.commons.beanutils.BeanMap;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.StringUtils;

import java.io.IOException;
import java.rmi.ServerException;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import com.google.common.base.Joiner;
import com.google.common.io.Files;

/**
 * resources service impl
 */
@Service
public class ResourcesServiceImpl extends BaseServiceImpl implements ResourcesService {

    private static final Logger logger = LoggerFactory.getLogger(ResourcesServiceImpl.class);

    @Autowired
    private ResourceMapper resourcesMapper;

    @Autowired
    private ResourceTaskMapper resourceTaskMapper;

    @Autowired
    private TaskDefinitionMapper taskDefinitionMapper;

    @Autowired
    private UdfFuncMapper udfFunctionMapper;

    @Autowired
    private TenantMapper tenantMapper;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private ResourceUserMapper resourceUserMapper;

    @Autowired
    private ProcessDefinitionMapper processDefinitionMapper;

    @Autowired(required = false)
    private StorageOperate storageOperate;

    /**
     * create directory
     *
     * @param loginUser   login user
     * @param name        alias
     * @param description description
     * @param type        type
     * @param pid         parent id
     * @param currentDir  current directory
     * @return create directory result
     */
    @Override
    @Transactional
    public Result<Object> createDirectory(User loginUser,
                                          String name,
                                          String description,
                                          ResourceType type,
                                          int pid,
                                          String currentDir) {
        Result<Object> result = new Result<>();
        String funcPermissionKey = type.equals(ResourceType.FILE) ? ApiFuncIdentificationConstant.FOLDER_ONLINE_CREATE
                : ApiFuncIdentificationConstant.UDF_FOLDER_ONLINE_CREATE;
        boolean canOperatorPermissions =
                canOperatorPermissions(loginUser, null, AuthorizationType.RESOURCE_FILE_ID, funcPermissionKey);
        if (!canOperatorPermissions) {
            putMsg(result, Status.NO_CURRENT_OPERATING_PERMISSION);
            return result;
        }

        result = checkResourceUploadStartupState();
        if (!result.getCode().equals(Status.SUCCESS.getCode())) {
            return result;
        }
        if (FileUtils.directoryTraversal(name)) {
            putMsg(result, Status.VERIFY_PARAMETER_NAME_FAILED);
            return result;
        }

        if (checkDescriptionLength(description)) {
            putMsg(result, Status.DESCRIPTION_TOO_LONG_ERROR);
            return result;
        }

        int tenantId = loginUser.getTenantId();
        Tenant tenant = tenantMapper.queryById(tenantId);
        if (tenant == null) {
            logger.error("tenant not exists");
            putMsg(result, Status.CURRENT_LOGIN_USER_TENANT_NOT_EXIST);
            return null;
        }

        String tenantCode = getTenantCode(loginUser.getId(), result);
        String userResRootPath = ResourceType.UDF.equals(type) ? storageOperate.getUdfDir(tenantCode)
                : storageOperate.getResDir(tenantCode);
        String fullName = !currentDir.contains(userResRootPath) ? userResRootPath + name : currentDir + name;

        try {
            if (checkResourceExists(fullName, type.ordinal())) {
                logger.error("resource directory {} has exist, can't recreate", fullName);
                putMsg(result, Status.RESOURCE_EXIST);
                return result;
            }
        } catch (Exception e) {
            throw new ServiceException("resource already exists, can't recreate");
        }

        // Date now = new Date();
        //
        // Resource resource = new Resource(pid, name, fullName, true, description, name, loginUser.getId(), type, 0,
        // now, now);
        //
        // try {
        // resourcesMapper.insert(resource);
        // putMsg(result, Status.SUCCESS);
        // permissionPostHandle(resource.getType(), loginUser, resource.getId());
        // Map<String, Object> resultMap = new HashMap<>();
        // for (Map.Entry<Object, Object> entry : new BeanMap(resource).entrySet()) {
        // if (!"class".equalsIgnoreCase(entry.getKey().toString())) {
        // resultMap.put(entry.getKey().toString(), entry.getValue());
        // }
        // }
        // result.setData(resultMap);
        // } catch (DuplicateKeyException e) {
        // logger.error("resource directory {} has exist, can't recreate", fullName);
        // putMsg(result, Status.RESOURCE_EXIST);
        // return result;
        // } catch (Exception e) {
        // logger.error("resource already exists, can't recreate ", e);
        // throw new ServiceException("resource already exists, can't recreate");
        // }

        // create directory in storage
        createDirectory(loginUser, fullName, type, result);
        return result;
    }

    private String getFullName(String currentDir, String name) {
        return currentDir.equals(FOLDER_SEPARATOR) ? String.format(FORMAT_SS, currentDir, name)
                : String.format(FORMAT_S_S, currentDir, name);
    }

    /**
     * create resource
     *
     * @param loginUser  login user
     * @param name       alias
     * @param desc       description
     * @param file       file
     * @param type       type
     * @param pid        parent id
     * @param currentDir current directory
     * @return create result code
     */
    @Override
    @Transactional
    public Result<Object> createResource(User loginUser,
                                         String name,
                                         String desc,
                                         ResourceType type,
                                         MultipartFile file,
                                         int pid,
                                         String currentDir) {
        Result<Object> result = new Result<>();
        String funcPermissionKey = type.equals(ResourceType.FILE) ? ApiFuncIdentificationConstant.FILE_UPLOAD
                : ApiFuncIdentificationConstant.UDF_UPLOAD;
        boolean canOperatorPermissions =
                canOperatorPermissions(loginUser, null, AuthorizationType.RESOURCE_FILE_ID, funcPermissionKey);
        if (!canOperatorPermissions) {
            putMsg(result, Status.NO_CURRENT_OPERATING_PERMISSION);
            return result;
        }
        result = checkResourceUploadStartupState();
        if (!result.getCode().equals(Status.SUCCESS.getCode())) {
            return result;
        }

        result = verifyPid(loginUser, pid);
        if (!result.getCode().equals(Status.SUCCESS.getCode())) {
            return result;
        }

        // make sure login user has tenant
        String tenantCode = getTenantCode(loginUser.getId(), result);
        if (StringUtils.isEmpty(tenantCode)) {
            return result;
        }

        result = verifyFile(name, type, file);
        if (!result.getCode().equals(Status.SUCCESS.getCode())) {
            return result;
        }

        // check resource name exists
        String userResRootPath = ResourceType.UDF.equals(type) ? storageOperate.getUdfDir(tenantCode)
                : storageOperate.getResDir(tenantCode);
        String currDirNFileName = !currentDir.contains(userResRootPath) ? userResRootPath + name : currentDir + name;

        try {
            if (checkResourceExists(currDirNFileName, type.ordinal())) {
                logger.error("resource {} has exist, can't recreate", RegexUtils.escapeNRT(name));
                putMsg(result, Status.RESOURCE_EXIST);
                return result;
            }
        } catch (Exception e) {
            throw new ServiceException("resource already exists, can't recreate");
        }

        if (currDirNFileName.length() > Constants.RESOURCE_FULL_NAME_MAX_LENGTH) {
            logger.error("resource {}'s full name {}' is longer than the max length {}", RegexUtils.escapeNRT(name),
                    currDirNFileName, Constants.RESOURCE_FULL_NAME_MAX_LENGTH);
            putMsg(result, Status.RESOURCE_FULL_NAME_TOO_LONG_ERROR);
            return result;
        }
        // try {
        // resourcesMapper.insert(resource);
        // updateParentResourceSize(resource, resource.getSize());
        // putMsg(result, Status.SUCCESS);
        // permissionPostHandle(resource.getType(), loginUser, resource.getId());
        // Map<String, Object> resultMap = new HashMap<>();
        // for (Map.Entry<Object, Object> entry : new BeanMap(resource).entrySet()) {
        // if (!"class".equalsIgnoreCase(entry.getKey().toString())) {
        // resultMap.put(entry.getKey().toString(), entry.getValue());
        // }
        // }
        // result.setData(resultMap);
        // } catch (Exception e) {
        // logger.error("resource already exists, can't recreate ", e);
        // throw new ServiceException("resource already exists, can't recreate");
        // }

        // fail upload
        if (!upload(loginUser, currDirNFileName, file, type)) {
            logger.error("upload resource: {} file: {} failed.", RegexUtils.escapeNRT(name),
                    RegexUtils.escapeNRT(file.getOriginalFilename()));
            putMsg(result, Status.STORE_OPERATE_CREATE_ERROR);
            throw new ServiceException(
                    String.format("upload resource: %s file: %s failed.", name, file.getOriginalFilename()));
        }
        return result;
    }

    /**
     * update the folder's size of the resource
     *
     * @param resource the current resource
     * @param size size
     */
    private void updateParentResourceSize(Resource resource, long size) {
        if (resource.getSize() > 0) {
            String[] splits = resource.getFullName().split("/");
            for (int i = 1; i < splits.length; i++) {
                String parentFullName = Joiner.on("/").join(Arrays.copyOfRange(splits, 0, i));
                if (StringUtils.isNotBlank(parentFullName)) {
                    List<Resource> resources =
                            resourcesMapper.queryResource(parentFullName, resource.getType().ordinal());
                    if (CollectionUtils.isNotEmpty(resources)) {
                        Resource parentResource = resources.get(0);
                        if (parentResource.getSize() + size >= 0) {
                            parentResource.setSize(parentResource.getSize() + size);
                        } else {
                            parentResource.setSize(0L);
                        }
                        resourcesMapper.updateById(parentResource);
                    }
                }
            }
        }
    }

    /**
     * check resource is exists
     *
     * @param fullName fullName
     * @param type     type
     * @return true if resource exists
     */
    private boolean checkResourceExists(String fullName, int type) {
        // Boolean existResource = resourcesMapper.existResource(fullName, type);
        Boolean existResource = false;
        try {
            existResource = storageOperate.exists(fullName);
        } catch (IOException e) {
            logger.error("AmazonServiceException when checking resource: " + fullName);
        }
        return Boolean.TRUE.equals(existResource);
    }

    /**
     * update resource
     *
     * @param loginUser  login user
     * @param resourceId resource id
     * @param name       name
     * @param desc       description
     * @param type       resource type
     * @param file       resource file
     * @return update result code
     */
    @Override
    @Transactional
    public Result<Object> updateResource(User loginUser,
                                         int resourceId,
                                         String resourceFullName,
                                         String resTenantCode,
                                         String name,
                                         String desc,
                                         ResourceType type,
                                         MultipartFile file) {
        Result<Object> result = new Result<>();

        String tenantCode = tenantMapper.queryById(loginUser.getTenantId()).getTenantCode();

        // new permission check
        if (!userIsValid(loginUser, tenantCode, resTenantCode)) {
            putMsg(result, Status.NO_CURRENT_OPERATING_PERMISSION);
            return result;
        }

        result = checkResourceUploadStartupState();
        if (!result.getCode().equals(Status.SUCCESS.getCode())) {
            return result;
        }

        String defaultPath = storageOperate.getResDir(tenantCode);

        StorageEntity resource;
        try {
            resource = storageOperate.getFileStatus(resourceFullName, defaultPath, resTenantCode, type);
        } catch (Exception e) {
            logger.error(e.getMessage() + " Resource path: {}", resourceFullName, e);
            putMsg(result, Status.RESOURCE_NOT_EXIST);
            throw new ServiceException(String.format(e.getMessage() + " Resource path: %s", resourceFullName));
        }

        if (!PropertyUtils.getResUploadStartupState()) {
            putMsg(result, Status.STORAGE_NOT_STARTUP);
            return result;
        }

        if (resource.isDirectory() && storageOperate.returnStorageType().equals(ResUploadType.S3)
                && !resource.getFileName().equals(name)) {
            putMsg(result, Status.S3_CANNOT_RENAME);
            return result;
        }

        if (file == null && name.equals(resource.getAlias()) && desc.equals(resource.getDescription())) {
            putMsg(result, Status.SUCCESS);
            return result;
        }

        // check if updated name of the resource already exists
        String originFullName = resource.getFullName();
        String originResourceName = resource.getAlias();

        // updated fullName
        String fullName = String.format(FORMAT_SS,
                originFullName.substring(0, originFullName.lastIndexOf(FOLDER_SEPARATOR) + 1), name);
        if (!originResourceName.equals(name)) {
            try {
                if (checkResourceExists(fullName, type.ordinal())) {
                    logger.error("resource {} already exists, can't recreate", fullName);
                    putMsg(result, Status.RESOURCE_EXIST);
                    return result;
                }
            } catch (Exception e) {
                throw new ServiceException(String.format("error occurs while querying resource: %s", fullName));
            }

        }

        result = verifyFile(name, type, file);
        if (!result.getCode().equals(Status.SUCCESS.getCode())) {
            return result;
        }

        // updateResource data
        Date now = new Date();
        long originFileSize = resource.getSize();

        resource.setAlias(name);
        resource.setFileName(name);
        resource.setFullName(fullName);
        resource.setDescription(desc);
        resource.setUpdateTime(now);
        if (file != null) {
            resource.setSize(file.getSize());
        }

        // TODO: revise this part when modifying HDFS
        // try {
        // if (resource.isDirectory()) {
        // List<Integer> childrenResource = listAllChildren(resource, false);
        // if (CollectionUtils.isNotEmpty(childrenResource)) {
        // String matcherFullName = Matcher.quoteReplacement(fullName);
        // List<Resource> childResourceList;
        // Integer[] childResIdArray = childrenResource.toArray(new Integer[childrenResource.size()]);
        // List<Resource> resourceList = resourcesMapper.listResourceByIds(childResIdArray);
        // childResourceList = resourceList.stream().map(t -> {
        // t.setFullName(t.getFullName().replaceFirst(originFullName, matcherFullName));
        // t.setUpdateTime(now);
        // return t;
        // }).collect(Collectors.toList());
        // resourcesMapper.batchUpdateResource(childResourceList);
        //
        // if (ResourceType.UDF.equals(resource.getType())) {
        // List<UdfFunc> udfFuncs = udfFunctionMapper.listUdfByResourceId(childResIdArray);
        // if (CollectionUtils.isNotEmpty(udfFuncs)) {
        // udfFuncs = udfFuncs.stream().map(t -> {
        // t.setResourceName(t.getResourceName().replaceFirst(originFullName, matcherFullName));
        // t.setUpdateTime(now);
        // return t;
        // }).collect(Collectors.toList());
        // udfFunctionMapper.batchUpdateUdfFunc(udfFuncs);
        // }
        // }
        // }
        // } else if (ResourceType.UDF.equals(resource.getType())) {
        // List<UdfFunc> udfFuncs = udfFunctionMapper.listUdfByResourceId(new Integer[]{resourceId});
        // if (CollectionUtils.isNotEmpty(udfFuncs)) {
        // udfFuncs = udfFuncs.stream().map(t -> {
        // t.setResourceName(fullName);
        // t.setUpdateTime(now);
        // return t;
        // }).collect(Collectors.toList());
        // udfFunctionMapper.batchUpdateUdfFunc(udfFuncs);
        // }
        //
        // }
        //
        // putMsg(result, Status.SUCCESS);
        // Map<String, Object> resultMap = new HashMap<>();
        // for (Map.Entry<Object, Object> entry : new BeanMap(resource).entrySet()) {
        // if (!Constants.CLASS.equalsIgnoreCase(entry.getKey().toString())) {
        // resultMap.put(entry.getKey().toString(), entry.getValue());
        // }
        // }
        // result.setData(resultMap);
        // } catch (Exception e) {
        // logger.error(Status.UPDATE_RESOURCE_ERROR.getMsg(), e);
        // throw new ServiceException(Status.UPDATE_RESOURCE_ERROR);
        // }

        // if name unchanged, return directly without moving on HDFS
        if (originResourceName.equals(name) && file == null) {
            return result;
        }

        if (file != null) {
            // fail upload
            if (!upload(loginUser, fullName, file, type)) {
                logger.error("upload resource: {} file: {} failed.", name,
                        RegexUtils.escapeNRT(file.getOriginalFilename()));
                putMsg(result, Status.HDFS_OPERATION_ERROR);
                throw new ServiceException(
                        String.format("upload resource: %s file: %s failed.", name, file.getOriginalFilename()));
            }
            if (!fullName.equals(originFullName)) {
                try {
                    storageOperate.delete(originFullName, false);
                } catch (IOException e) {
                    logger.error(e.getMessage(), e);
                    throw new ServiceException(String.format("delete resource: %s failed.", originFullName));
                }
            }

            // updateParentResourceSize(resource, resource.getSize() - originFileSize);
            return result;
        }

        // get the path of dest file in hdfs
        // String destHdfsFileName = storageOperate.getFileName(resource.getType(), tenantCode, fullName);
        String destHdfsFileName = fullName;
        try {
            logger.info("start  copy {} -> {}", originFullName, destHdfsFileName);
            storageOperate.copy(originFullName, destHdfsFileName, true, true);
        } catch (Exception e) {
            logger.error(MessageFormat.format(" copy {0} -> {1} fail", originFullName, destHdfsFileName), e);
            putMsg(result, Status.HDFS_COPY_FAIL);
            throw new ServiceException(Status.HDFS_COPY_FAIL);
        }

        // TODO: now update db info
        Integer oldResourceId = resourceTaskMapper.existResourceByFullName(originFullName, type);
        if (oldResourceId != null) {
            ResourcesTask updatedResource = new ResourcesTask(fullName, type);
            updatedResource.setId(oldResourceId);
            resourceTaskMapper.updateById(updatedResource);
        }

        return result;
    }

    private Result<Object> verifyFile(String name, ResourceType type, MultipartFile file) {
        Result<Object> result = new Result<>();
        putMsg(result, Status.SUCCESS);

        if (FileUtils.directoryTraversal(name)) {
            logger.error("file alias name {} verify failed", name);
            putMsg(result, Status.VERIFY_PARAMETER_NAME_FAILED);
            return result;
        }

        if (file != null && FileUtils.directoryTraversal(Objects.requireNonNull(file.getOriginalFilename()))) {
            logger.error("file original name {} verify failed", file.getOriginalFilename());
            putMsg(result, Status.VERIFY_PARAMETER_NAME_FAILED);
            return result;
        }

        if (file != null) {
            // file is empty
            if (file.isEmpty()) {
                logger.error("file is empty: {}", RegexUtils.escapeNRT(file.getOriginalFilename()));
                putMsg(result, Status.RESOURCE_FILE_IS_EMPTY);
                return result;
            }

            // file suffix
            String fileSuffix = Files.getFileExtension(file.getOriginalFilename());
            String nameSuffix = Files.getFileExtension(name);

            // determine file suffix
            if (!fileSuffix.equalsIgnoreCase(nameSuffix)) {
                // rename file suffix and original suffix must be consistent
                logger.error("rename file suffix and original suffix must be consistent: {}",
                        RegexUtils.escapeNRT(file.getOriginalFilename()));
                putMsg(result, Status.RESOURCE_SUFFIX_FORBID_CHANGE);
                return result;
            }

            // If resource type is UDF, only jar packages are allowed to be uploaded, and the suffix must be .jar
            if (Constants.UDF.equals(type.name()) && !JAR.equalsIgnoreCase(fileSuffix)) {
                logger.error(Status.UDF_RESOURCE_SUFFIX_NOT_JAR.getMsg());
                putMsg(result, Status.UDF_RESOURCE_SUFFIX_NOT_JAR);
                return result;
            }
            if (file.getSize() > Constants.MAX_FILE_SIZE) {
                logger.error("file size is too large: {}", RegexUtils.escapeNRT(file.getOriginalFilename()));
                putMsg(result, Status.RESOURCE_SIZE_EXCEED_LIMIT);
                return result;
            }
        }
        return result;
    }

    /**
     * query resources list paging
     *
     * @param loginUser login user
     * @param type      resource type
     * @param searchVal search value
     * @param pageNo    page number
     * @param pageSize  page size
     * @return resource list page
     */
    @Override
    public Result queryResourceListPaging(User loginUser, int directoryId, String fullName, String resTenantCode,
                                          ResourceType type, String searchVal, Integer pageNo, Integer pageSize) {
        Result<Object> result = new Result<>();

        PageInfo<StorageEntity> pageInfo = new PageInfo<>(pageNo, pageSize);
//        Set<Integer> resourcesIds = resourcePermissionCheckService
//                .userOwnedResourceIdsAcquisition(checkResourceType(type), loginUser.getId(), logger);
//        if (resourcesIds.isEmpty()) {
//            result.setData(pageInfo);
//            putMsg(result, Status.SUCCESS);
//            return result;
//        }

        int tenantId = loginUser.getTenantId();
        Tenant tenant = tenantMapper.queryById(tenantId);
        if (tenant == null) {
            logger.error("tenant not exists");
            putMsg(result, Status.CURRENT_LOGIN_USER_TENANT_NOT_EXIST);
            return result;
        }

        // check user type
        String tenantCode = tenantMapper.queryById(tenantId).getTenantCode();

        if (!userIsValid(loginUser, tenantCode, resTenantCode)) {
            putMsg(result, Status.NO_CURRENT_OPERATING_PERMISSION);
            return result;
        }

        String defaultPath = "";
        List<StorageEntity> resourcesList = new ArrayList<>();

        if (isAdmin(loginUser) && "".equals(fullName)) {
            // list all tenants' resources to admin users in the root directory
            List<Tenant> tenantList = tenantMapper.selectList(null);
            for (Tenant tenantEntity : tenantList) {
                defaultPath = storageOperate.getResDir(tenantEntity.getTenantCode());
                if (type.equals(ResourceType.UDF)) {
                    defaultPath = storageOperate.getUdfDir(tenantEntity.getTenantCode());
                }
                try {
                    resourcesList.addAll(storageOperate.listFilesStatus(defaultPath, defaultPath,
                            tenantEntity.getTenantCode(), type));
                } catch (Exception e) {
                    logger.error(e.getMessage() + " Resource path: {}", defaultPath, e);
                    putMsg(result, Status.RESOURCE_NOT_EXIST);
                    throw new ServiceException(String.format(e.getMessage() + " Resource path: %s", defaultPath));
                }
            }
        } else {
            defaultPath = storageOperate.getResDir(tenantCode);
            if (type.equals(ResourceType.UDF)) {
                defaultPath = storageOperate.getUdfDir(tenantCode);
            }

            try {
                if ("".equals(fullName)){
                    resourcesList = storageOperate.listFilesStatus(defaultPath, defaultPath, tenantCode, type);
                } else {
                    resourcesList = storageOperate.listFilesStatus(fullName, defaultPath, tenantCode, type);
                }
            } catch (Exception e) {
                logger.error(e.getMessage() + " Resource path: {}", fullName, e);
                putMsg(result, Status.RESOURCE_NOT_EXIST);
                throw new ServiceException(String.format(e.getMessage() + " Resource path: %s", fullName));
            }
        }

        // remove leading and trailing spaces in searchVal
        String trimmedSearchVal = searchVal != null ? searchVal.trim() : "";
        // filter based on trimmed searchVal
        List<StorageEntity> filteredResourceList = resourcesList.stream()
                .filter(x -> x.getFileName().matches("(.*)" + trimmedSearchVal + "(.*)")).collect(Collectors.toList());
        // inefficient pagination
        List<StorageEntity> slicedResourcesList = filteredResourceList.stream().skip((pageNo - 1) * pageSize)
                .limit(pageSize).collect(Collectors.toList());

        pageInfo.setTotal(resourcesList.size());
        pageInfo.setTotalList(slicedResourcesList);
        result.setData(pageInfo);
        putMsg(result, Status.SUCCESS);
        return result;
    }

    /**
     * create directory
     * xxx The steps to verify resources are cumbersome and can be optimized
     *
     * @param loginUser login user
     * @param fullName  full name
     * @param type      resource type
     * @param result    Result
     */
    private void createDirectory(User loginUser, String fullName, ResourceType type, Result<Object> result) {
        String tenantCode = tenantMapper.queryById(loginUser.getTenantId()).getTenantCode();
        // String directoryName = storageOperate.getFileName(type, tenantCode, fullName);
        String resourceRootPath = storageOperate.getDir(type, tenantCode);
        try {
            if (!storageOperate.exists(resourceRootPath)) {
                storageOperate.createTenantDirIfNotExists(tenantCode);
            }

            if (!storageOperate.mkdir(tenantCode, fullName)) {
                logger.error("create resource directory {}  failed", fullName);
                putMsg(result, Status.STORE_OPERATE_CREATE_ERROR);
                throw new ServiceException(String.format("create resource directory: %s failed.", fullName));
            }
        } catch (Exception e) {
            logger.error("create resource directory {}  failed", fullName);
            putMsg(result, Status.STORE_OPERATE_CREATE_ERROR);
            throw new ServiceException(String.format("create resource directory: %s failed.", fullName));
        }
    }

    /**
     * upload file to hdfs
     *
     * @param loginUser login user
     * @param fullName  full name
     * @param file      file
     */
    private boolean upload(User loginUser, String fullName, MultipartFile file, ResourceType type) {
        // save to local
        String fileSuffix = Files.getFileExtension(file.getOriginalFilename());
        String nameSuffix = Files.getFileExtension(fullName);

        // determine file suffix
        if (!fileSuffix.equalsIgnoreCase(nameSuffix)) {
            return false;
        }
        // query tenant
        String tenantCode = tenantMapper.queryById(loginUser.getTenantId()).getTenantCode();
        // random file name
        String localFilename = FileUtils.getUploadFilename(tenantCode, UUID.randomUUID().toString());

        // save file to hdfs, and delete original file
        // String fileName = storageOperate.getFileName(type, tenantCode, fullName);
        String resourcePath = storageOperate.getDir(type, tenantCode);
        try {
            // if tenant dir not exists
            if (!storageOperate.exists(resourcePath)) {
                storageOperate.createTenantDirIfNotExists(tenantCode);
            }
            org.apache.dolphinscheduler.api.utils.FileUtils.copyInputStreamToFile(file, localFilename);
            storageOperate.upload(tenantCode, localFilename, fullName, true, true);
        } catch (Exception e) {
            FileUtils.deleteFile(localFilename);
            logger.error(e.getMessage(), e);
            return false;
        }
        return true;
    }

    /**
     * query resource list
     *
     * @param loginUser login user
     * @param type      resource type
     * @return resource list
     */
    @Override
    public Map<String, Object> queryResourceList(User loginUser, ResourceType type, String fullName) {
        Map<String, Object> result = new HashMap<>();

        String funcPermissionKey = type.equals(ResourceType.FILE) ? ApiFuncIdentificationConstant.FILE_VIEW
                : ApiFuncIdentificationConstant.UDF_FILE_VIEW;
        boolean canOperatorPermissions =
                canOperatorPermissions(loginUser, null, AuthorizationType.RESOURCE_FILE_ID, funcPermissionKey);
        if (!canOperatorPermissions) {
            putMsg(result, Status.NO_CURRENT_OPERATING_PERMISSION);
            return result;
        }

        int tenantId = loginUser.getTenantId();
        Tenant tenant = tenantMapper.queryById(tenantId);
        if (tenant == null) {
            logger.error("tenant not exists");
            putMsg(result, Status.CURRENT_LOGIN_USER_TENANT_NOT_EXIST);
            return null;
        }

        String tenantCode = tenant.getTenantCode();
        String defaultPath = "";
        List<StorageEntity> resourcesList = new ArrayList<>();

        if ("".equals(fullName)) {
            if (isAdmin(loginUser)) {
                List<User> userList = userMapper.selectList(null);
                Set<String> visitedTenantEntityCode = new HashSet<>();
                for (User userEntity : userList) {
                    String tenantEntityCode = tenantMapper.queryById(userEntity.getTenantId()).getTenantCode();
                    defaultPath = storageOperate.getResDir(tenantEntityCode);
                    if (type.equals(ResourceType.UDF)) {
                        defaultPath = storageOperate.getUdfDir(tenantEntityCode);
                    }
                    resourcesList.addAll(storageOperate.listFilesStatusRecursively(defaultPath, defaultPath,
                            tenantEntityCode, type));
                    visitedTenantEntityCode.add(tenantEntityCode);
                }
            } else {
                defaultPath = storageOperate.getResDir(tenantCode);
                if (type.equals(ResourceType.UDF)) {
                    defaultPath = storageOperate.getUdfDir(tenantCode);
                }

                resourcesList = storageOperate.listFilesStatusRecursively(defaultPath, defaultPath, tenantCode, type);
            }
        } else {
            defaultPath = storageOperate.getResDir(tenantCode);
            if (type.equals(ResourceType.UDF)) {
                defaultPath = storageOperate.getUdfDir(tenantCode);
            }

            resourcesList = storageOperate.listFilesStatusRecursively(fullName, defaultPath, tenantCode, type);
        }

        Visitor resourceTreeVisitor = new ResourceTreeVisitor(resourcesList);
        result.put(Constants.DATA_LIST, resourceTreeVisitor.visit(defaultPath).getChildren());
        putMsg(result, Status.SUCCESS);

        return result;
    }

    /**
     * query resource list by program type
     *
     * @param loginUser login user
     * @param type      resource type
     * @return resource list
     */
    @Override
    public Result<Object> queryResourceByProgramType(User loginUser, ResourceType type, ProgramType programType) {
        Result<Object> result = new Result<>();

        Set<Integer> resourceIds = resourcePermissionCheckService
                .userOwnedResourceIdsAcquisition(checkResourceType(type), loginUser.getId(), logger);
        if (resourceIds.isEmpty()) {
            result.setData(Collections.emptyList());
            putMsg(result, Status.SUCCESS);
            return result;
        }
        List<Resource> allResourceList = resourcesMapper.selectBatchIds(resourceIds);

        String suffix = ".jar";
        if (programType != null) {
            switch (programType) {
                case JAVA:
                case SCALA:
                    break;
                case PYTHON:
                    suffix = ".py";
                    break;
                default:
            }
        }
        List<Resource> resources = new ResourceFilter(suffix, new ArrayList<>(allResourceList)).filter();
        // Transform into StorageEntity for compatibility
        List<StorageEntity> transformedResourceList = resources.stream()
                .map(resource -> new StorageEntity.Builder()
                        .fullName(resource.getFullName())
                        .pfullName(resourcesMapper.selectById(resource.getPid()).getFullName())
                        .isDirectory(resource.isDirectory())
                        .alias(resource.getAlias())
                        .id(resource.getId())
                        .type(resource.getType())
                        .description(resource.getDescription())
                        .build()).collect(Collectors.toList());
        Visitor visitor = new ResourceTreeVisitor(transformedResourceList);
        result.setData(visitor.visit("").getChildren());
        putMsg(result, Status.SUCCESS);
        return result;
    }

    /**
     * delete resource
     *
     * @param loginUser  login user
     * @param resourceId resource id
     * @return delete result code
     * @throws IOException exception
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result<Object> delete(User loginUser, int resourceId, String fullName,
                                 String resTenantCode) throws IOException {
        Result<Object> resultCheck = new Result<>();

        String tenantCode = tenantMapper.queryById(loginUser.getTenantId()).getTenantCode();

        Result<Object> result = checkResourceUploadStartupState();
        if (StringUtils.isEmpty(tenantCode)) {
            return result;
        }

        String defaultPath = storageOperate.getResDir(tenantCode);
        StorageEntity resource;
        try {
            resource = storageOperate.getFileStatus(fullName, defaultPath, resTenantCode, ResourceType.FILE);
        } catch (Exception e) {
            logger.error(e.getMessage() + " Resource path: {}", fullName, e);
            putMsg(result, Status.RESOURCE_NOT_EXIST);
            throw new ServiceException(String.format(e.getMessage() + " Resource path: %s", fullName));
        }

        if (resource == null) {
            putMsg(resultCheck, Status.RESOURCE_NOT_EXIST);
            return resultCheck;
        }

        if (!result.getCode().equals(Status.SUCCESS.getCode())) {
            return result;
        }

        if (!userIsValid(loginUser, tenantCode, resTenantCode)) {
            putMsg(result, Status.NO_CURRENT_OPERATING_PERMISSION);
            return result;
        }

        // get all resource id_news of process definitions those are released
        List<Map<String, Object>> list = processDefinitionMapper.listResources();
        Map<Integer, Set<Long>> resourceProcessMap =
                ResourceProcessDefinitionUtils.getResourceObjectMap(list, "code", Long.class);
        Map<Integer, Set<Integer>> resourceTaskMap =
                ResourceProcessDefinitionUtils.getResourceObjectMap(list, "td_id", Integer.class);
        Set<Integer> resourceIdInUseSet = resourceProcessMap.keySet();

        // recursively delete a folder
        Set<Integer> resourcesIdNeedToDeleteSet = new HashSet<>();
        List<String> allChildren = storageOperate.listFilesStatusRecursively(fullName, defaultPath,
                resTenantCode, ResourceType.FILE).stream().map(storageEntity -> storageEntity.getFullName()).collect(Collectors.toList());


        String[] allChildrenFullNameArray = allChildren.stream().toArray(String[]::new);
        resourcesIdNeedToDeleteSet.addAll(
                resourceTaskMapper.selectBatchFullNames(allChildrenFullNameArray, ResourceType.FILE));

        // Integer[] arr = new Integer[resourcesIdSet.size()];
        // List<Map<String, Object>> resourceInUse =
        // processDefinitionMapper.queryResourcesInUseWithinResourceArray(resourcesIdSet.toArray(arr));

        Integer[] needDeleteResourceIdArray =
                resourcesIdNeedToDeleteSet.toArray(new Integer[resourcesIdNeedToDeleteSet.size()]);

        // TODO: is this still correct?
//        if (needDeleteResourceIdArray.length >= 2) {
//            logger.error("can't be deleted,because There are files or folders in the current directory:{}", resource);
//            putMsg(result, Status.RESOURCE_HAS_FOLDER, resource.getFileName());
//            return result;
//        }

        // if resource type is UDF,need check whether it is bound by UDF function
        if (resource.getType() == (ResourceType.UDF)) {
            List<UdfFunc> udfFuncs = udfFunctionMapper.listUdfByResourceFullName(allChildrenFullNameArray);
            if (CollectionUtils.isNotEmpty(udfFuncs)) {
                logger.error("can't be deleted,because it is bound by UDF functions:{}", udfFuncs);
                putMsg(result, Status.UDF_RESOURCE_IS_BOUND, udfFuncs.get(0).getFuncName());
                return result;
            }
        }

        resourceIdInUseSet.retainAll(resourcesIdNeedToDeleteSet);
        if (CollectionUtils.isNotEmpty(resourceIdInUseSet)) {
            logger.error("can't be deleted,because it is used of process definition");
            for (Integer resId : resourceIdInUseSet) {
                logger.error("resource id:{} is used of process definition {}", resId, resourceProcessMap.get(resId));
            }
            putMsg(result, Status.RESOURCE_IS_USED);
            return result;
        }

        // update parent size
        // resourcesMapper.selectBatchIds(Arrays.asList(needDeleteResourceIdArray)).forEach(item -> {
        // updateParentResourceSize(item, item.getSize() * -1);
        // });

        // delete data in database
        if (needDeleteResourceIdArray.length > 0) {
            // update resource_ids_new, task_params in task definition
            for (int resourceIdNeedToDelete : needDeleteResourceIdArray) {
                String resourceFullNameToDelete = resourceTaskMapper.selectById(resourceIdNeedToDelete).getFullName();
                // new method getResourceTaskMap in util
                Set<Integer> taskSetContainsResId = resourceTaskMap.get(resourceIdNeedToDelete);
                if (taskSetContainsResId != null) {
                    for (Integer taskDefinitionId: taskSetContainsResId) {
                        TaskDefinition td = taskDefinitionMapper.selectById(taskDefinitionId);
                        td.setTaskParams(RemoveResourceFromResourceList(resourceFullNameToDelete, td.getTaskParams()));
                        // IdsNew could be null, should be a res = idsNew == null ? "" : idsNew;
                        td.setResourceIdsNew(RemoveResourceFromIdsNew(resourceIdNeedToDelete, td.getResourceIdsNew()));
                        int isSuccessful = taskDefinitionMapper.updateById(td);
                        if (isSuccessful != 1) {
                            logger.error("delete task relation error while deleting {}", resourceFullNameToDelete);
                            putMsg(result, Status.DELETE_TASK_PROCESS_RELATION_ERROR);
                            return result;
                        }
                    }
                }
            }

            resourceTaskMapper.deleteIds(needDeleteResourceIdArray);
        }

        // delete file on hdfs,S3
        storageOperate.delete(fullName, allChildren, true);

        putMsg(result, Status.SUCCESS);

        return result;
    }

    private String RemoveResourceFromResourceList(String stringToDelete, String taskParameter) {
        Map<String, Object> taskParameters = JSONUtils.parseObject(
                taskParameter,
                new TypeReference<Map<String, Object>>() {
                });
        if (taskParameters.containsKey("resourceList")) {
            String resourceListStr = JSONUtils.toJsonString(taskParameters.get("resourceList"));
            List<ResourceInfo> resourceInfoList = JSONUtils.toList(resourceListStr, ResourceInfo.class);
            List<ResourceInfo> updatedResourceInfoList = resourceInfoList.stream()
                    .filter(Objects::nonNull)
                    .filter(resourceInfo -> !resourceInfo.getResourceName().equals(stringToDelete))
                    .collect(Collectors.toList());

            taskParameters.put("resourceList", updatedResourceInfoList);
            return JSONUtils.toJsonString(taskParameters);
        }
        return taskParameter;
    }

    private String RemoveResourceFromIdsNew(int idToDelete, String idNews) {

        String[] resourceIds = idNews.split(",");
        Set<Integer> resourceIdSet = Arrays.stream(resourceIds)
                .map(Integer::parseInt)
                .filter(integerId -> !integerId.equals(idToDelete))
                .collect(Collectors.toSet());
        return Joiner.on(",").join(resourceIdSet);
    }

    /**
     * verify resource by name and type
     *
     * @param loginUser login user
     * @param fullName  resource full name
     * @param type      resource type
     * @return true if the resource name not exists, otherwise return false
     */
    @Override
    public Result<Object> verifyResourceName(String fullName, ResourceType type, User loginUser) {
        Result<Object> result = new Result<>();
        String funcPermissionKey = type.equals(ResourceType.FILE) ? ApiFuncIdentificationConstant.FILE_RENAME
                : ApiFuncIdentificationConstant.UDF_FILE_VIEW;
        boolean canOperatorPermissions =
                canOperatorPermissions(loginUser, null, AuthorizationType.RESOURCE_FILE_ID, funcPermissionKey);
        if (!canOperatorPermissions) {
            putMsg(result, Status.NO_CURRENT_OPERATING_PERMISSION);
            return result;
        }
        putMsg(result, Status.SUCCESS);
        if (checkResourceExists(fullName, type.ordinal())) {
            logger.error("resource type:{} name:{} has exist, can't create again.", type,
                    RegexUtils.escapeNRT(fullName));
            putMsg(result, Status.RESOURCE_EXIST);
        }

        return result;
    }

    /**
     * verify resource by full name or pid and type
     *
     * @param fileName resource file name
     * @param id       resource id
     * @param type     resource type
     * @return true if the resource full name or pid not exists, otherwise return false
     */
    @Override
    public Result<Object> queryResource(User loginUser, String fileName, Integer id, ResourceType type,
                                        String resTenantCode) {
        Result<Object> result = new Result<>();
        if (StringUtils.isBlank(fileName) && id == null) {
            putMsg(result, Status.REQUEST_PARAMS_NOT_VALID_ERROR);
            return result;
        }

        String tenantCode = getTenantCode(loginUser.getId(), result);

        if (!userIsValid(loginUser, tenantCode, resTenantCode)) {
            putMsg(result, Status.NO_CURRENT_OPERATING_PERMISSION);
            return result;
        }

        String defaultPath = storageOperate.getResDir(resTenantCode);
        if (type.equals(ResourceType.UDF)) {
            defaultPath = storageOperate.getUdfDir(resTenantCode);
        }

        StorageEntity file;
        try {
            file = storageOperate.getFileStatus(defaultPath + fileName, defaultPath, resTenantCode, type);
        } catch (Exception e) {
            logger.error(e.getMessage() + " Resource path: {}", defaultPath + fileName, e);
            putMsg(result, Status.RESOURCE_NOT_EXIST);
            return result;
        }

        Integer resourceId = resourceTaskMapper.existResourceByFullName(defaultPath + fileName, type);
        if (resourceId != null) {
            file.setId(resourceId);
        }

        putMsg(result, Status.SUCCESS);
        result.setData(file);
        return result;
    }

    /**
     * get resource by id
     * @param id        resource id
     * @return resource
     */
    @Override
    public Result<Object> queryResourceById(User loginUser, Integer id, String fullName, String resTenantCode,
                                            ResourceType type) throws IOException {
        Result<Object> result = new Result<>();

        int tenantId = loginUser.getTenantId();
        Tenant tenant = tenantMapper.queryById(tenantId);
        if (tenant == null) {
            logger.error("tenant not exists");
            putMsg(result, Status.CURRENT_LOGIN_USER_TENANT_NOT_EXIST);
            return null;
        }
        String tenantCode = tenant.getTenantCode();
        // new permission check
        if (!userIsValid(loginUser, tenantCode, resTenantCode)) {
            putMsg(result, Status.NO_CURRENT_OPERATING_PERMISSION);
            return result;
        }

        String defaultPath = storageOperate.getResDir(resTenantCode);
        if (type.equals(ResourceType.UDF)) {
            defaultPath = storageOperate.getUdfDir(resTenantCode);
        }

        StorageEntity file;
        try {
            file = storageOperate.getFileStatus(fullName, defaultPath, resTenantCode, type);
            Integer resourceId = resourceTaskMapper.existResourceByFullName(file.getFullName(), file.getType());
            if (resourceId != null) {
                file.setId(resourceId);
            }
        } catch (Exception e) {
            logger.error(e.getMessage() + " Resource path: {}", fullName, e);
            putMsg(result, Status.RESOURCE_NOT_EXIST);
            throw new ServiceException(String.format(e.getMessage() + " Resource path: %s", fullName));
        }

        putMsg(result, Status.SUCCESS);
        result.setData(file);
        return result;
    }

    /**
     * view resource file online
     *
     * @param resourceId  resource id
     * @param skipLineNum skip line number
     * @param limit       limit
     * @return resource content
     */
    @Override
    public Result<Object> readResource(User loginUser, String resourceId, String fullName, String resTenantCode,int skipLineNum, int limit) {
        Result<Object> result = checkResourceUploadStartupState();
        if (!result.getCode().equals(Status.SUCCESS.getCode())) {
            return result;
        }

        String tenantCode = getTenantCode(loginUser.getId(), result);

        // new permission check
        if (!userIsValid(loginUser, tenantCode, resTenantCode)) {
            putMsg(result, Status.NO_CURRENT_OPERATING_PERMISSION);
            return result;
        }

        // check preview or not by file suffix
        String nameSuffix = Files.getFileExtension(fullName);
        String resourceViewSuffixes = FileUtils.getResourceViewSuffixes();
        if (StringUtils.isNotEmpty(resourceViewSuffixes)) {
            List<String> strList = Arrays.asList(resourceViewSuffixes.split(","));
            if (!strList.contains(nameSuffix)) {
                logger.error("resource suffix {} not support view,  resource id {}", nameSuffix, fullName);
                putMsg(result, Status.RESOURCE_SUFFIX_NOT_SUPPORT_VIEW);
                return result;
            }
        }

        List<String> content = new ArrayList<>();
        try {
            if (storageOperate.exists(fullName)) {
                content = storageOperate.vimFile(tenantCode, fullName, skipLineNum, limit);
            } else {
                logger.error("read file {} not exist in storage", fullName);
                putMsg(result, Status.RESOURCE_FILE_NOT_EXIST, fullName);
            }

        } catch (Exception e) {
            logger.error("Resource {} read failed", fullName, e);
            putMsg(result, Status.HDFS_OPERATION_ERROR);
        }

        putMsg(result, Status.SUCCESS);
        Map<String, Object> map = new HashMap<>();
        map.put(ALIAS, fullName);
        map.put(CONTENT, String.join("\n", content));
        result.setData(map);

        return result;
    }

    /**
     * create resource file online
     *
     * @param loginUser  login user
     * @param type       resource type
     * @param fileName   file name
     * @param fileSuffix file suffix
     * @param desc       description
     * @param content    content
     * @param pid        pid
     * @param currentDir current directory
     * @return create result code
     */
    @Override
    @Transactional
    public Result<Object> onlineCreateResource(User loginUser, ResourceType type, String fileName, String fileSuffix,
                                               String desc, String content, int pid, String currentDir) {
        Result<Object> result = new Result<>();
        boolean canOperatorPermissions = canOperatorPermissions(loginUser, null, AuthorizationType.RESOURCE_FILE_ID,
                ApiFuncIdentificationConstant.FILE_ONLINE_CREATE);
        if (!canOperatorPermissions) {
            putMsg(result, Status.NO_CURRENT_OPERATING_PERMISSION);
            return result;
        }

        result = checkResourceUploadStartupState();
        if (!result.getCode().equals(Status.SUCCESS.getCode())) {
            return result;
        }
        if (FileUtils.directoryTraversal(fileName)) {
            putMsg(result, Status.VERIFY_PARAMETER_NAME_FAILED);
            return result;
        }

        // check file suffix
        String nameSuffix = fileSuffix.trim();
        String resourceViewSuffixes = FileUtils.getResourceViewSuffixes();
        if (StringUtils.isNotEmpty(resourceViewSuffixes)) {
            List<String> strList = Arrays.asList(resourceViewSuffixes.split(","));
            if (!strList.contains(nameSuffix)) {
                logger.error("resource suffix {} not support create", nameSuffix);
                putMsg(result, Status.RESOURCE_SUFFIX_NOT_SUPPORT_VIEW);
                return result;
            }
        }

        String name = fileName.trim() + "." + nameSuffix;
        // String fullName = getFullName(currentDir, name);
        int tenantId = loginUser.getTenantId();
        Tenant tenant = tenantMapper.queryById(tenantId);
        if (tenant == null) {
            logger.error("tenant not exists");
            putMsg(result, Status.CURRENT_LOGIN_USER_TENANT_NOT_EXIST);
            return null;
        }

        String tenantCode = getTenantCode(loginUser.getId(), result);

        String fullName = "";
        String userResRootPath = storageOperate.getResDir(tenantCode);
        if (!currentDir.contains(userResRootPath)) {
            fullName = userResRootPath + name;
        } else {
            fullName = currentDir + name;
        }

        result = verifyResource(loginUser, type, fullName, pid);
        if (!result.getCode().equals(Status.SUCCESS.getCode())) {
            return result;
        }

        result = uploadContentToStorage(loginUser, fullName, tenantCode, content);
        if (!result.getCode().equals(Status.SUCCESS.getCode())) {
            throw new ServiceException(result.getMsg());
        }
        return result;
    }

    /**
     * create or update resource.
     * If the folder is not already created, it will be
     *
     * @param loginUser user who create or update resource
     * @param fileFullName The full name of resource.Includes path and suffix.
     * @param desc description of resource
     * @param content content of resource
     * @return create result code
     */
    @Override
    @Transactional
    public Result<Object> onlineCreateOrUpdateResourceWithDir(User loginUser, String fileFullName, String desc,
                                                              String content) {
        // TODO: need update to third party service
        if (checkResourceExists(fileFullName, ResourceType.FILE.ordinal())) {
            Resource resource = resourcesMapper.queryResource(fileFullName, ResourceType.FILE.ordinal()).get(0);
            Result<Object> result = this.updateResourceContent(loginUser, resource.getId(), fileFullName,
                    resource.getUserName(), content);
            if (result.getCode() == Status.SUCCESS.getCode()) {
                resource.setDescription(desc);
                Map<String, Object> resultMap = new HashMap<>();
                for (Map.Entry<Object, Object> entry : new BeanMap(resource).entrySet()) {
                    if (!Constants.CLASS.equalsIgnoreCase(entry.getKey().toString())) {
                        resultMap.put(entry.getKey().toString(), entry.getValue());
                    }
                }
                result.setData(resultMap);
            }
            return result;
        } else {
            String resourceSuffix = fileFullName.substring(fileFullName.indexOf(PERIOD) + 1);
            String fileNameWithSuffix = fileFullName.substring(fileFullName.lastIndexOf(FOLDER_SEPARATOR) + 1);
            String resourceDir = fileFullName.replace(fileNameWithSuffix, EMPTY_STRING);
            String resourceName = fileNameWithSuffix.replace(PERIOD + resourceSuffix, EMPTY_STRING);
            String[] dirNames = resourceDir.split(FOLDER_SEPARATOR);
            int pid = -1;
            StringBuilder currDirPath = new StringBuilder();
            for (String dirName : dirNames) {
                if (StringUtils.isNotEmpty(dirName)) {
                    pid = queryOrCreateDirId(loginUser, pid, currDirPath.toString(), dirName);
                    currDirPath.append(FOLDER_SEPARATOR).append(dirName);
                }
            }
            return this.onlineCreateResource(
                    loginUser, ResourceType.FILE, resourceName, resourceSuffix, desc, content, pid,
                    currDirPath.toString());
        }
    }

    @Override
    @Transactional
    public Integer createOrUpdateResource(String userName, String fullName, String description,
                                          String resourceContent) {
        User user = userMapper.queryByUserNameAccurately(userName);
        int suffixLabelIndex = fullName.indexOf(PERIOD);
        if (suffixLabelIndex == -1) {
            String msg = String.format("The suffix of file can not be empty : %s", fullName);
            logger.error(msg);
            throw new IllegalArgumentException(msg);
        }
        if (!fullName.startsWith(FOLDER_SEPARATOR)) {
            fullName = FOLDER_SEPARATOR + fullName;
        }
        Result<Object> createResult = onlineCreateOrUpdateResourceWithDir(
                user, fullName, description, resourceContent);
        if (createResult.getCode() == Status.SUCCESS.getCode()) {
            Map<String, Object> resultMap = (Map<String, Object>) createResult.getData();
            return (int) resultMap.get("id");
        }
        String msg = String.format("Can not create or update resource : %s", fullName);
        logger.error(msg);
        throw new IllegalArgumentException(msg);
    }

    private int queryOrCreateDirId(User user, int pid, String currentDir, String dirName) {
        String dirFullName = currentDir + FOLDER_SEPARATOR + dirName;
        if (checkResourceExists(dirFullName, ResourceType.FILE.ordinal())) {
            List<Resource> resourceList = resourcesMapper.queryResource(dirFullName, ResourceType.FILE.ordinal());
            return resourceList.get(0).getId();
        } else {
            // create dir
            Result<Object> createDirResult = this.createDirectory(
                    user, dirName, EMPTY_STRING, ResourceType.FILE, pid, currentDir);
            if (createDirResult.getCode() == Status.SUCCESS.getCode()) {
                Map<String, Object> resultMap = (Map<String, Object>) createDirResult.getData();
                return resultMap.get("id") == null ? -1 : (Integer) resultMap.get("id");
            } else {
                String msg = String.format("Can not create dir %s", dirFullName);
                logger.error(msg);
                throw new IllegalArgumentException(msg);
            }
        }
    }

    private void permissionPostHandle(ResourceType resourceType, User loginUser, Integer resourceId) {
        AuthorizationType authorizationType =
                resourceType.equals(ResourceType.FILE) ? AuthorizationType.RESOURCE_FILE_ID
                        : AuthorizationType.UDF_FILE;
        permissionPostHandle(authorizationType, loginUser.getId(), Collections.singletonList(resourceId), logger);
    }

    private Result<Object> checkResourceUploadStartupState() {
        Result<Object> result = new Result<>();
        putMsg(result, Status.SUCCESS);
        // if resource upload startup
        if (!PropertyUtils.getResUploadStartupState()) {
            logger.error("resource upload startup state: {}", PropertyUtils.getResUploadStartupState());
            putMsg(result, Status.STORAGE_NOT_STARTUP);
            return result;
        }
        return result;
    }

    private Result<Object> verifyResource(User loginUser, ResourceType type, String fullName, int pid) {
        Result<Object> result = verifyResourceName(fullName, type, loginUser);
        if (!result.getCode().equals(Status.SUCCESS.getCode())) {
            return result;
        }
        return verifyPid(loginUser, pid);
    }

    private Result<Object> verifyPid(User loginUser, int pid) {
        Result<Object> result = new Result<>();
        putMsg(result, Status.SUCCESS);
        if (pid != -1) {
            Resource parentResource = resourcesMapper.selectById(pid);
            if (parentResource == null) {
                putMsg(result, Status.PARENT_RESOURCE_NOT_EXIST);
                return result;
            }
            if (!canOperator(loginUser, parentResource.getUserId())) {
                putMsg(result, Status.USER_NO_OPERATION_PERM);
                return result;
            }
        }
        return result;
    }

    /**
     * updateProcessInstance resource
     *
     * @param resourceId resource id
     * @param content    content
     * @return update result cod
     */
    @Override
    @Transactional
    public Result<Object> updateResourceContent(User loginUser, int resourceId, String fullName, String resTenantCode,
                                                String content) {
        Result<Object> result = checkResourceUploadStartupState();
        if (!result.getCode().equals(Status.SUCCESS.getCode())) {
            return result;
        }

        StorageEntity resource;
        try {
            resource = storageOperate.getFileStatus(fullName, "", resTenantCode, ResourceType.FILE);
        } catch (Exception e) {
            logger.error("error occurred when fetching resource information ,  resource id {}", resourceId);
            putMsg(result, Status.RESOURCE_NOT_EXIST);
            return result;
        }

        if (resource == null) {
            logger.error("read file not exist,  resource id {}", resourceId);
            putMsg(result, Status.RESOURCE_NOT_EXIST);
            return result;
        }
        // String funcPermissionKey = resource.getType().equals(ResourceType.FILE) ?
        // ApiFuncIdentificationConstant.FILE_UPDATE : ApiFuncIdentificationConstant.UDF_UPDATE;
        // boolean canOperatorPermissions = canOperatorPermissions(loginUser, new Object[]{resourceId},
        // checkResourceType(resource.getType()), funcPermissionKey);
        // if (!canOperatorPermissions) {
        // putMsg(result, Status.NO_CURRENT_OPERATING_PERMISSION);
        // return result;
        // }
        // check can edit by file suffix
        String nameSuffix = Files.getFileExtension(resource.getAlias());
        String resourceViewSuffixes = FileUtils.getResourceViewSuffixes();
        if (StringUtils.isNotEmpty(resourceViewSuffixes)) {
            List<String> strList = Arrays.asList(resourceViewSuffixes.split(","));
            if (!strList.contains(nameSuffix)) {
                logger.error("resource suffix {} not support updateProcessInstance,  resource id {}", nameSuffix,
                        resourceId);
                putMsg(result, Status.RESOURCE_SUFFIX_NOT_SUPPORT_VIEW);
                return result;
            }
        }

        // String tenantCode = getTenantCode(resource.getUserId(), result);
        if (StringUtils.isEmpty(resTenantCode)) {
            return result;
        }
        // long originFileSize = resource.getSize();
        // resource.setSize(content.getBytes().length);
        // resource.setUpdateTime(new Date());
        // resourcesMapper.updateById(resource);

        result = uploadContentToStorage(loginUser, resource.getFullName(), resTenantCode, content);
        // updateParentResourceSize(resource, resource.getSize() - originFileSize);

        if (!result.getCode().equals(Status.SUCCESS.getCode())) {
            throw new ServiceException(result.getMsg());
        }
        return result;
    }

    /**
     * @param fullName resource full name
     * @param tenantCode   tenant code
     * @param content      content
     * @return result
     */
    private Result<Object> uploadContentToStorage(User loginUser, String fullName, String tenantCode, String content) {
        Result<Object> result = new Result<>();
        String localFilename = "";
        try {
            localFilename = FileUtils.getUploadFilename(tenantCode, UUID.randomUUID().toString());

            if (!FileUtils.writeContent2File(content, localFilename)) {
                // write file fail
                logger.error("file {} fail, content is {}", localFilename, RegexUtils.escapeNRT(content));
                putMsg(result, Status.RESOURCE_NOT_EXIST);
                return result;
            }

            // get resource file path
            String resourcePath = storageOperate.getResDir(tenantCode);
            logger.info("resource  path is {}, resource dir is {}", fullName, resourcePath);

            if (!storageOperate.exists(resourcePath)) {
                // create if tenant dir not exists
                storageOperate.createTenantDirIfNotExists(tenantCode);
            }
            if (storageOperate.exists(fullName)) {
                storageOperate.delete(fullName, false);
            }

            storageOperate.upload(tenantCode, localFilename, fullName, true, true);
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
            result.setCode(Status.HDFS_OPERATION_ERROR.getCode());
            result.setMsg(String.format("copy %s to hdfs %s fail", localFilename, fullName));
            return result;
        }
        putMsg(result, Status.SUCCESS);
        return result;
    }

    /**
     * download file
     *
     * @param resourceId resource id
     * @return resource content
     * @throws IOException exception
     */
    @Override
    public org.springframework.core.io.Resource downloadResource(User loginUser, int resourceId,
                                                                 String fullName) throws IOException {
        // if resource upload startup
        if (!PropertyUtils.getResUploadStartupState()) {
            logger.error("resource upload startup state: {}", PropertyUtils.getResUploadStartupState());
            throw new ServiceException("hdfs not startup");
        }


        // String funcPermissionKey = resource.getType().equals(ResourceType.FILE) ?
        // ApiFuncIdentificationConstant.FILE_DOWNLOAD : ApiFuncIdentificationConstant.UDF_DOWNLOAD;
        // boolean canOperatorPermissions = canOperatorPermissions(loginUser, new Object[]{resourceId},
        // checkResourceType(resource.getType()), funcPermissionKey);
        // if (!canOperatorPermissions){
        // logger.error("{}: {}", Status.NO_CURRENT_OPERATING_PERMISSION.getMsg(),
        // PropertyUtils.getResUploadStartupState());
        // throw new ServiceException(Status.NO_CURRENT_OPERATING_PERMISSION.getMsg());
        // }
        // resource.isDirectory()
        if (fullName.endsWith("/")) {
            logger.error("resource id {} is directory,can't download it", fullName);
            throw new ServiceException("can't download directory");
        }

        int userId = loginUser.getId();
        User user = userMapper.selectById(userId);
        if (user == null) {
            logger.error("user id {} not exists", userId);
            throw new ServiceException(String.format("resource owner id %d not exist", userId));
        }

        String tenantCode = "";

        if (user.getTenantId() != 0) {
            Tenant tenant = tenantMapper.queryById(user.getTenantId());
            if (tenant == null) {
                logger.error("tenant id {} not exists", user.getTenantId());
                throw new ServiceException(
                        String.format("The tenant id %d of resource owner not exist", user.getTenantId()));
            }
            tenantCode = tenant.getTenantCode();
        }

        String[] aliasArr = fullName.split("/");
        String alias = aliasArr[aliasArr.length - 1];
        String localFileName = FileUtils.getDownloadFilename(alias);
        logger.info("resource  path is {}, download local filename is {}", alias, localFileName);

        try {
            storageOperate.download(tenantCode, fullName, localFileName, false, true);
            return org.apache.dolphinscheduler.api.utils.FileUtils.file2Resource(localFileName);
        } catch (IOException e) {
            logger.error("download resource error, the path is {}, and local filename is {}, the error message is {}",
                    fullName, localFileName, e.getMessage());
            throw new ServerException("download the resource file failed ,it may be related to your storage");
        }
    }

    /**
     * list all file
     *
     * @param loginUser login user
     * @param userId    user id
     * @return unauthorized result code
     */
    @Override
    public Map<String, Object> authorizeResourceTree(User loginUser, Integer userId) {
        Map<String, Object> result = new HashMap<>();
        if (!resourcePermissionCheckService.functionDisabled()) {
            putMsg(result, Status.FUNCTION_DISABLED);
            return result;
        }

        List<Resource> resourceList;
        if (isAdmin(loginUser)) {
            // admin gets all resources except userId
            resourceList = resourcesMapper.queryResourceExceptUserId(userId);
        } else {
            // non-admins users get their own resources
            resourceList = resourcesMapper.queryResourceListAuthored(loginUser.getId(), -1);
        }
        List<ResourceComponent> list;
        if (CollectionUtils.isNotEmpty(resourceList)) {
            // Transform into StorageEntity for compatibility
            List<StorageEntity> transformedResourceList = resourceList.stream()
                    .map(resource -> new StorageEntity.Builder()
                            .fullName(resource.getFullName())
                            .pfullName(resourcesMapper.selectById(resource.getPid()).getFullName())
                            .isDirectory(resource.isDirectory())
                            .alias(resource.getAlias())
                            .id(resource.getId())
                            .type(resource.getType())
                            .description(resource.getDescription())
                            .build()).collect(Collectors.toList());
            Visitor visitor = new ResourceTreeVisitor(transformedResourceList);
            list = visitor.visit("").getChildren();
        } else {
            list = new ArrayList<>(0);
        }

        result.put(Constants.DATA_LIST, list);
        putMsg(result, Status.SUCCESS);
        return result;
    }

    @Override
    public Resource queryResourcesFileInfo(String userName, String fullName) {
        // TODO: It is used in PythonGateway, should be revised
        User user = userMapper.queryByUserNameAccurately(userName);
        Result<Object> resourceResponse = this.queryResource(user, fullName, null, ResourceType.FILE, "");
        if (resourceResponse.getCode() != Status.SUCCESS.getCode()) {
            String msg = String.format("Can not find valid resource by name %s", fullName);
            throw new IllegalArgumentException(msg);
        }
        return (Resource) resourceResponse.getData();
    }

    /**
     * unauthorized file
     *
     * @param loginUser login user
     * @param userId    user id
     * @return unauthorized result code
     */
    @Override
    public Map<String, Object> unauthorizedFile(User loginUser, Integer userId) {
        Map<String, Object> result = new HashMap<>();

        List<Resource> resourceList;
        if (isAdmin(loginUser)) {
            // admin gets all resources except userId
            resourceList = resourcesMapper.queryResourceExceptUserId(userId);
        } else {
            // non-admins users get their own resources
            resourceList = resourcesMapper.queryResourceListAuthored(loginUser.getId(), -1);
        }
        List<Resource> list;
        if (resourceList != null && !resourceList.isEmpty()) {
            Set<Resource> resourceSet = new HashSet<>(resourceList);
            List<Resource> authedResourceList = queryResourceList(userId, Constants.AUTHORIZE_WRITABLE_PERM);
            getAuthorizedResourceList(resourceSet, authedResourceList);
            list = new ArrayList<>(resourceSet);
        } else {
            list = new ArrayList<>(0);
        }
        // Transform into StorageEntity for compatibility
        List<StorageEntity> transformedResourceList = resourceList.stream()
                .map(resource -> new StorageEntity.Builder()
                        .fullName(resource.getFullName())
                        .pfullName(resourcesMapper.selectById(resource.getPid()).getFullName())
                        .isDirectory(resource.isDirectory())
                        .alias(resource.getAlias())
                        .id(resource.getId())
                        .type(resource.getType())
                        .description(resource.getDescription())
                        .build()).collect(Collectors.toList());
        Visitor visitor = new ResourceTreeVisitor(transformedResourceList);
        result.put(Constants.DATA_LIST, visitor.visit("").getChildren());
        putMsg(result, Status.SUCCESS);
        return result;
    }

    /**
     * unauthorized udf function
     *
     * @param loginUser login user
     * @param userId    user id
     * @return unauthorized result code
     */
    @Override
    public Map<String, Object> unauthorizedUDFFunction(User loginUser, Integer userId) {
        Map<String, Object> result = new HashMap<>();
        if (!resourcePermissionCheckService.functionDisabled()) {
            putMsg(result, Status.FUNCTION_DISABLED);
            return result;
        }

        List<UdfFunc> udfFuncList;
        if (isAdmin(loginUser)) {
            // admin gets all udfs except userId
            udfFuncList = udfFunctionMapper.queryUdfFuncExceptUserId(userId);
        } else {
            // non-admins users get their own udfs
            udfFuncList = udfFunctionMapper.selectByMap(Collections.singletonMap("user_id", loginUser.getId()));
        }
        List<UdfFunc> resultList = new ArrayList<>();
        Set<UdfFunc> udfFuncSet;
        if (CollectionUtils.isNotEmpty(udfFuncList)) {
            udfFuncSet = new HashSet<>(udfFuncList);

            List<UdfFunc> authedUDFFuncList = udfFunctionMapper.queryAuthedUdfFunc(userId);

            getAuthorizedResourceList(udfFuncSet, authedUDFFuncList);
            resultList = new ArrayList<>(udfFuncSet);
        }
        result.put(Constants.DATA_LIST, resultList);
        putMsg(result, Status.SUCCESS);
        return result;
    }

    /**
     * authorized udf function
     *
     * @param loginUser login user
     * @param userId    user id
     * @return authorized result code
     */
    @Override
    public Map<String, Object> authorizedUDFFunction(User loginUser, Integer userId) {
        Map<String, Object> result = new HashMap<>();
        if (!resourcePermissionCheckService.functionDisabled()) {
            putMsg(result, Status.FUNCTION_DISABLED);
            return result;
        }
        List<UdfFunc> udfFuncs = udfFunctionMapper.queryAuthedUdfFunc(userId);
        result.put(Constants.DATA_LIST, udfFuncs);
        putMsg(result, Status.SUCCESS);
        return result;
    }

    /**
     * authorized file
     *
     * @param loginUser login user
     * @param userId    user id
     * @return authorized result
     */
    @Override
    public Map<String, Object> authorizedFile(User loginUser, Integer userId) {
        Map<String, Object> result = new HashMap<>();
        if (!resourcePermissionCheckService.functionDisabled()) {
            putMsg(result, Status.FUNCTION_DISABLED);
            return result;
        }

        List<Resource> authedResources = queryResourceList(userId, Constants.AUTHORIZE_WRITABLE_PERM);
        // Transform into StorageEntity for compatibility
        List<StorageEntity> transformedResourceList = authedResources.stream()
                .map(resource -> new StorageEntity.Builder()
                        .fullName(resource.getFullName())
                        .pfullName(resourcesMapper.selectById(resource.getPid()).getFullName())
                        .isDirectory(resource.isDirectory())
                        .alias(resource.getAlias())
                        .id(resource.getId())
                        .type(resource.getType())
                        .description(resource.getDescription())
                        .build()).collect(Collectors.toList());
        Visitor visitor = new ResourceTreeVisitor(transformedResourceList);
        String visit = JSONUtils.toJsonString(visitor.visit(""), SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
        logger.info(visit);
        String jsonTreeStr =
                JSONUtils.toJsonString(visitor.visit("").getChildren(), SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
        logger.info(jsonTreeStr);
        result.put(Constants.DATA_LIST, visitor.visit("").getChildren());
        putMsg(result, Status.SUCCESS);
        return result;
    }

    /**
     * get authorized resource list
     *
     * @param resourceSet        resource set
     * @param authedResourceList authorized resource list
     */
    private void getAuthorizedResourceList(Set<?> resourceSet, List<?> authedResourceList) {
        Set<?> authedResourceSet;
        if (CollectionUtils.isNotEmpty(authedResourceList)) {
            authedResourceSet = new HashSet<>(authedResourceList);
            resourceSet.removeAll(authedResourceSet);
        }
    }

    /**
     * get tenantCode by UserId
     *
     * @param userId user id
     * @param result return result
     * @return tenant code
     */
    private String getTenantCode(int userId, Result<Object> result) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            logger.error("user {} not exists", userId);
            putMsg(result, Status.USER_NOT_EXIST, userId);
            return null;
        }

        Tenant tenant = tenantMapper.queryById(user.getTenantId());
        if (tenant == null) {
            logger.error("tenant not exists");
            putMsg(result, Status.CURRENT_LOGIN_USER_TENANT_NOT_EXIST);
            return null;
        }
        return tenant.getTenantCode();
    }

    /**
     * list all children id
     *
     * @param resource    resource
     * @param containSelf whether add self to children list
     * @return all children id
     */
    List<Integer> listAllChildren(Resource resource, boolean containSelf) {
        List<Integer> childList = new ArrayList<>();
        if (resource.getId() != null && containSelf) {
            childList.add(resource.getId());
        }

        if (resource.isDirectory()) {
            listAllChildren(resource.getId(), childList);
        }
        return childList;
    }

    /**
     * list all children id
     *
     * @param resourceId resource id
     * @param childList  child list
     */
    void listAllChildren(int resourceId, List<Integer> childList) {
        List<Integer> children = resourcesMapper.listChildren(resourceId);
        for (int childId : children) {
            childList.add(childId);
            listAllChildren(childId, childList);
        }
    }

    /**
     * query authored resource list (own and authorized)
     *
     * @param loginUser login user
     * @param type      ResourceType
     * @return all authored resource list
     */
    private List<Resource> queryAuthoredResourceList(User loginUser, ResourceType type) {
        Set<Integer> resourceIds = resourcePermissionCheckService
                .userOwnedResourceIdsAcquisition(checkResourceType(type), loginUser.getId(), logger);
        if (resourceIds.isEmpty()) {
            return Collections.emptyList();
        }
        List<Resource> resources = resourcesMapper.selectBatchIds(resourceIds);
        resources = resources.stream().filter(rs -> rs.getType() == type).collect(Collectors.toList());
        return resources;
    }

    /**
     * query resource list by userId and perm
     *
     * @param userId userId
     * @param perm   perm
     * @return resource list
     */
    private List<Resource> queryResourceList(Integer userId, int perm) {
        List<Integer> resIds = resourceUserMapper.queryResourcesIdListByUserIdAndPerm(userId, perm);
        return CollectionUtils.isEmpty(resIds) ? new ArrayList<>() : resourcesMapper.queryResourceListById(resIds);
    }

    private AuthorizationType checkResourceType(ResourceType type) {
        return type.equals(ResourceType.FILE) ? AuthorizationType.RESOURCE_FILE_ID : AuthorizationType.UDF_FILE;
    }

    /**
     * check permission by comparing login user's tenantCode with tenantCode in the request
     *
     * @param loginUser user who currently logs in
     * @param tenantCode  loginUser's tenantCode
     * @param resTenantCode tenantCode in the request field "resTenantCode", can be different from the login user in the case of admin users.
     * @return isValid
     */
    private boolean userIsValid(User loginUser, String tenantCode, String resTenantCode) {
        if (!isAdmin(loginUser)) {
            resTenantCode = resTenantCode == null ? "" : resTenantCode;
            if (!"".equals(resTenantCode) && !resTenantCode.equals(tenantCode)) {
                // if an ordinary user directly send a query API with a different tenantCode and fullName "",
                // still he/she does not have read permission.
                return false;
            }
        }

        return true;
    }
}
