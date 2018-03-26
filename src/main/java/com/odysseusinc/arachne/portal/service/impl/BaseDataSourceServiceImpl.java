/*
 *
 * Copyright 2017 Observational Health Data Sciences and Informatics
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Company: Odysseus Data Services, Inc.
 * Product Owner/Architecture: Gregory Klebanov
 * Authors: Pavel Grafkin, Alexandr Ryabokon, Vitaly Koulakov, Anton Gackovka, Maria Pozhidaeva, Mikhail Mironov
 * Created: September 17, 2017
 *
 */

package com.odysseusinc.arachne.portal.service.impl;

import com.odysseusinc.arachne.commons.api.v1.dto.CommonModelType;
import com.odysseusinc.arachne.portal.api.v1.dto.SearchDataCatalogDTO;
import com.odysseusinc.arachne.portal.config.WebSecurityConfig;
import com.odysseusinc.arachne.portal.exception.FieldException;
import com.odysseusinc.arachne.portal.exception.NotExistException;
import com.odysseusinc.arachne.portal.exception.PermissionDeniedException;
import com.odysseusinc.arachne.portal.model.DataSource;
import com.odysseusinc.arachne.portal.model.DataSourceStatus;
import com.odysseusinc.arachne.portal.model.IDataSource;
import com.odysseusinc.arachne.portal.model.IUser;
import com.odysseusinc.arachne.portal.model.Skill;
import com.odysseusinc.arachne.portal.model.StudyDataSourceLink;
import com.odysseusinc.arachne.portal.model.solr.SolrCollection;
import com.odysseusinc.arachne.portal.repository.BaseDataSourceRepository;
import com.odysseusinc.arachne.portal.repository.BaseRawDataSourceRepository;
import com.odysseusinc.arachne.portal.service.BaseDataSourceService;
import com.odysseusinc.arachne.portal.service.BaseSolrService;
import com.odysseusinc.arachne.portal.service.BaseUserService;
import com.odysseusinc.arachne.portal.service.TenantService;
import com.odysseusinc.arachne.portal.service.impl.solr.FieldList;
import com.odysseusinc.arachne.portal.service.impl.solr.SearchResult;
import com.odysseusinc.arachne.portal.service.impl.solr.SolrField;
import com.odysseusinc.arachne.portal.service.mail.ArachneMailSender;
import com.odysseusinc.arachne.portal.service.mail.NewDataSourceMailMessage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import javax.persistence.criteria.CriteriaBuilder;
import javax.persistence.criteria.CriteriaQuery;
import javax.persistence.criteria.Predicate;
import javax.persistence.criteria.Root;
import javax.persistence.criteria.Subquery;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.convert.support.GenericConversionService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.prepost.PostAuthorize;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;

import javax.persistence.EntityManager;
import javax.persistence.TypedQuery;

@Transactional(rollbackFor = Exception.class)
public abstract class BaseDataSourceServiceImpl<
        DS extends IDataSource, // tenant dependent DataSource
        SF extends SolrField> implements BaseDataSourceService<DS> {

    private static final Logger log = LoggerFactory.getLogger(BaseDataSourceServiceImpl.class);
    protected BaseDataSourceRepository<DS> dataSourceRepository;
    protected BaseSolrService<SF> solrService;
    protected GenericConversionService conversionService;
    protected TenantService tenantService;
    protected BaseRawDataSourceRepository<DS> rawDataSourceRepository;
    protected final BaseUserService<IUser, Skill> userService;
    protected final ArachneMailSender arachneMailSender;
    protected EntityManager entityManager;

    public BaseDataSourceServiceImpl(BaseSolrService<SF> solrService,
                                     BaseDataSourceRepository<DS> dataSourceRepository,
                                     GenericConversionService conversionService,
                                     TenantService tenantService,
                                     BaseRawDataSourceRepository<DS> rawDataSourceRepository,
                                     BaseUserService<IUser, Skill> userService,
                                     ArachneMailSender arachneMailSender,
                                     EntityManager entityManager) {

        this.solrService = solrService;
        this.dataSourceRepository = dataSourceRepository;
        this.conversionService = conversionService;
        this.tenantService = tenantService;
        this.rawDataSourceRepository = rawDataSourceRepository;
        this.userService = userService;
        this.arachneMailSender = arachneMailSender;
        this.entityManager = entityManager;
    }

    @Override
    @PreAuthorize("hasPermission(#dataSource, "
            + "T(com.odysseusinc.arachne.portal.security.ArachnePermission).CREATE_DATASOURCE)")
    @PostAuthorize("@ArachnePermissionEvaluator.addPermissions(principal, returnObject )")
    public DS createOrRestoreDataSource(DS dataSource)
            throws FieldException,
            NotExistException {

        final Boolean virtual = dataSource.getDataNode().getVirtual();
        beforeCreate(dataSource, virtual);

        if (!CommonModelType.CDM.equals(dataSource.getModelType())) {
            dataSource.setCdmVersion(null);
        }
        DS savedDataSource = dataSourceRepository.save(dataSource);
        try {
            afterCreate(savedDataSource, virtual);
        } catch (PermissionDeniedException e) {
            log.error("AfterCreated handler error", e);
        }
        return savedDataSource;
    }

    protected void beforeCreate(DS dataSource, boolean virtual) {

        dataSource.setPublished(false);
        dataSource.setCreated(new Date());
        dataSource.setTenants(tenantService.getDefault());
    }

    protected void afterCreate(DS dataSource, boolean virtual) throws PermissionDeniedException {

        List<IUser> admins = userService.getAllAdmins("name", true);
        notifyNewDataSourceRegistered(admins, dataSource);
    }

    protected void notifyNewDataSourceRegistered(List<IUser> admins, DS dataSource) throws PermissionDeniedException {

        if (Objects.nonNull(admins)) {
            admins.forEach(u -> arachneMailSender.send(
                    new NewDataSourceMailMessage<>(WebSecurityConfig.getDefaultPortalURI(), u, dataSource)
            ));
        }
    }

    protected QueryResponse solrSearch(final SolrQuery solrQuery) throws IOException, SolrServerException, NoSuchFieldException {

        return solrService.search(
                SolrCollection.DATA_SOURCES.getName(),
                solrQuery,
                Boolean.TRUE
        );
    }

    public SearchResult<DS> search(
            SolrQuery solrQuery
    ) throws IOException, SolrServerException, NoSuchFieldException {

        List<DS> dataSourceList;

        QueryResponse solrResponse = solrSearch(solrQuery);

        List<Long> docIdList = solrResponse.getResults()
                .stream()
                .map(solrDoc -> Long.parseLong(solrDoc.get(BaseSolrServiceImpl.ID).toString()))
                .collect(Collectors.toList());

        // We need to repeat sorting, because repository doesn't prevent order of passed ids
        dataSourceList = dataSourceRepository.findByIdInAndDeletedIsNullAndPublishedTrue(docIdList);
        dataSourceList.sort(Comparator.comparing(item -> docIdList.indexOf(item.getId())));

        return new SearchResult<>(solrQuery, solrResponse, dataSourceList);
    }

    @Override
    public SearchResult<DS> search(SolrQuery solrQuery, IUser user)
            throws NoSuchFieldException, IOException, SolrServerException {

        solrQuery = addFilterQuery(solrQuery, user);
        SearchResult<DS> result = search(solrQuery);
        result.setExcludedOptions(getExcludedOptions(user));
        return result;
    }

    @Transactional
    @PreAuthorize("hasRole('ROLE_ADMIN') || hasPermission(#dataSource, "
            + "T(com.odysseusinc.arachne.portal.security.ArachnePermission).EDIT_DATASOURCE)")
    @PostAuthorize("@ArachnePermissionEvaluator.addPermissions(principal, returnObject )")
    @Override
    public DS updateInAnyTenant(DS dataSource)
            throws IllegalAccessException, NoSuchFieldException, SolrServerException, IOException {

        DS forUpdate = getNotDeletedByIdInAnyTenant(dataSource.getId());
        forUpdate = baseUpdate(forUpdate, dataSource);

        beforeUpdate(forUpdate, dataSource);
        DS savedDataSource = rawDataSourceRepository.save(forUpdate);
        afterUpdate(savedDataSource);
        return savedDataSource;
    }

    private DS baseUpdate(DS exist, DS dataSource) {

        if (dataSource.getName() != null) {
            exist.setName(dataSource.getName());
        }

        if (dataSource.getModelType() != null) {
            exist.setModelType(dataSource.getModelType());
        }

        if (dataSource.getCdmVersion() != null) {
            exist.setCdmVersion(CommonModelType.CDM.equals(dataSource.getModelType()) ? dataSource.getCdmVersion() : null);
        }

        if (dataSource.getOrganization() != null) {
            exist.setOrganization(dataSource.getOrganization());
        }

        if (dataSource.getPublished() != null) {
            exist.setPublished(dataSource.getPublished());
        }

        if (!CollectionUtils.isEmpty(dataSource.getTenants())) {
            exist.setTenants(dataSource.getTenants());
        }

        if (dataSource.getDbmsType() != null) {
            exist.setDbmsType(dataSource.getDbmsType());
        }
        return exist;
    }

    protected void beforeUpdate(DS target, DS dataSource) {

    }

    protected void afterUpdate(DS dataSource)
            throws IllegalAccessException, NoSuchFieldException, SolrServerException, IOException {

        if (!dataSource.getDataNode().getVirtual() && dataSource.getPublished() != null
                && dataSource.getPublished()) {
            indexBySolr(dataSource);
        }
    }

    @Override
    @PreAuthorize("hasRole('ROLE_ADMIN') || hasPermission(#dataSourceId, 'RawDataSource', "
            + "T(com.odysseusinc.arachne.portal.security.ArachnePermission).ACCESS_DATASOURCE)")
    @PostAuthorize("@ArachnePermissionEvaluator.addPermissions(principal, returnObject )")
    public DS getNotDeletedByIdInAnyTenant(Long dataSourceId) {

        return rawDataSourceRepository.findByIdAndDeletedIsNull(dataSourceId)
                .orElseThrow(() -> new NotExistException(DataSource.class));
    }

    @Override
    @PreAuthorize("hasPermission(#dataSourceId, 'DataSource', "
            + "T(com.odysseusinc.arachne.portal.security.ArachnePermission).ACCESS_DATASOURCE)")
    @PostAuthorize("@ArachnePermissionEvaluator.addPermissions(principal, returnObject )")
    public DS getNotDeletedById(Long dataSourceId) {

        return dataSourceRepository.findByIdAndDeletedIsNull(dataSourceId).orElseThrow(() -> new NotExistException(getType()));
    }

    @Override
    public DS getByIdUnsecured(Long id) {

        if (id == null) {
            throw new NotExistException("id is null", getType());
        }
        DS dataSource = rawDataSourceRepository.findOne(id);
        if (dataSource == null) {
            throw new NotExistException(getType());
        }
        return dataSource;
    }

    protected abstract <T extends DS> Class<T> getType();

    @Override
    public List<DS> getAllNotDeletedIsNotVirtualUnsecured() {

        return dataSourceRepository.getByDataNodeVirtualAndDeletedIsNullAndPublishedTrue(false);
    }

    private List<DS> getAllNotDeletedAndIsNotVirtualFromAllTenants() {

        return dataSourceRepository.getAllNotDeletedAndIsNotVirtualAndPublishedTrueFromAllTenants();
    }

    @Override
    public DS findByUuidUnsecured(String uuid) throws NotExistException {

        if (uuid == null) {
            throw new NotExistException("uuid is null", getType());
        }
        DS dataSource = dataSourceRepository.findByUuid(uuid);
        if (dataSource == null) {
            throw new NotExistException(getType());
        }
        return dataSource;
    }

    @Override
    public Page<DS> suggestDataSource(final String query, final Long studyId, final Long userId,
                                      PageRequest pageRequest) {
        List<DataSourceStatus> BAD_STATUSES = Arrays.asList(DataSourceStatus.DELETED, DataSourceStatus.DECLINED);
        final String[] split = query.trim().split(" ");

        CriteriaBuilder cb = this.entityManager.getCriteriaBuilder();
        CriteriaQuery<DS> cq = cb.createQuery(getType());
        Root<DS> root = cq.from(getType());

        Subquery sq = cq.subquery(Long.class);
        Root<StudyDataSourceLink> dsLink = sq.from(StudyDataSourceLink.class);
        sq.select(dsLink.get("dataSource").get("id"));
        sq.where(cb.and(cb.equal(dsLink.get("study").get("id"), studyId),
                        cb.not(dsLink.get("status").in(BAD_STATUSES))));

        cq.select(root);
        Predicate nameClause = cb.conjunction();  // TRUE
        if (split.length > 1 || (split.length == 1 && !split[0].equals("") )) {
            List<Predicate> predictList = new ArrayList<>();
            for (String one: split) {
                predictList.add(cb.like(cb.lower(root.get("name")), one + "%"));
                predictList.add(cb.like(cb.lower(root.get("dataNode").get("name")), one + "%"));
            }
            nameClause = cb.or(predictList.toArray(new Predicate[] {}));
        }

        cq.where(cb.and(cb.not(root.get("id").in(sq)),
                        nameClause,
                        cb.isNull(root.get("deleted")),
                        cb.isTrue(root.get("published")),
                        cb.isFalse(root.get("dataNode").get("virtual"))));

        TypedQuery<DS> typedQuery = this.entityManager.createQuery(cq);
        List<DS> list = typedQuery.setFirstResult(pageRequest.getOffset())
                            .setMaxResults(pageRequest.getPageSize())
                            .getResultList();
        return new PageImpl<>(list, pageRequest, list.size());
    }

    protected Page<DS> doSuggestDataSource(String query, Long userId, Long studyId, PageRequest pageRequest) {

        return dataSourceRepository.suggest(query, studyId, pageRequest);
    }

    @PreAuthorize("hasPermission(#id, 'DataSource', "
            + "T(com.odysseusinc.arachne.portal.security.ArachnePermission).DELETE_DATASOURCE)")
    @Transactional
    @Override
    public void delete(Long id) {

        log.info("Deleting datasource with id={}", id);
        rawDataSourceRepository.delete(id);
    }

    @PreAuthorize("hasPermission(#id, 'DataSource', "
            + "T(com.odysseusinc.arachne.portal.security.ArachnePermission).EDIT_DATASOURCE)")
    @Transactional
    @Override
    public void unpublish(Long id) throws IOException, SolrServerException {

        log.info("Unpublishing datasource with id={}", id);
        DS dataSource = getByIdUnsecured(id);

        if (dataSource.getPublished() != null && dataSource.getPublished()) {
            dataSource.setPublished(false);
            dataSourceRepository.save(dataSource);

            solrService.delete(SolrCollection.DATA_SOURCES, String.valueOf(id));
        }
    }

    @Override
    public Page<DS> getUserDataSources(final String query, final Long userId, PageRequest pageRequest) {

        final String[] split = query.trim().split(" ");
        String suggestRequest = "%(" + String.join("|", split) + ")%";
        return dataSourceRepository.getUserDataSources(suggestRequest, userId, pageRequest);
    }

    public FieldList<SF> getSolrFields() {

        FieldList<SF> fieldList = new FieldList<>();

        fieldList.addAll(solrService.getFieldsOfClass(getType()));
        fieldList.addAll(getExtraSolrFilelds());
        return fieldList;
    }

    protected List<SF> getExtraSolrFilelds() {

        return Collections.emptyList();
    }

    @Override
    public void indexAllBySolr() throws IllegalAccessException, NoSuchFieldException, SolrServerException, IOException {

        solrService.deleteAll(SolrCollection.DATA_SOURCES);
        final List<DS> dataSourceList = getAllNotDeletedAndIsNotVirtualFromAllTenants();
        for (final DS dataSource : dataSourceList) {
            indexBySolr(dataSource);
        }
    }

    protected SolrQuery addFilterQuery(SolrQuery solrQuery, IUser user) throws NoSuchFieldException {

        return solrQuery;
    }

    private Map<String, List<String>> getExcludedOptions(IUser user) throws NoSuchFieldException,
            IOException, SolrServerException {

        SolrQuery solrQuery = conversionService.convert(new SearchDataCatalogDTO(true), SolrQuery.class);
        solrQuery = addFilterQuery(solrQuery, user);

        QueryResponse solrResponse = solrSearch(solrQuery);
        SearchResult<Long> searchResult = new SearchResult<>(solrQuery, solrResponse, Collections.<Long>emptyList());
        return searchResult.excludedOptions();
    }

    public void indexBySolr(DS dataSource)
            throws IOException, SolrServerException, NoSuchFieldException, IllegalAccessException {

        solrService.indexBySolr(dataSource);
    }

    public List<DS> findByIdsAndNotDeleted(List<Long> dataSourceIds) {

        return rawDataSourceRepository.findByIdInAndDeletedIsNull(dataSourceIds);
    }

    public abstract List<DS> getAllByUserId(Long userId);
}
