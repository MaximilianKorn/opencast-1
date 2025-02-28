/**
 * Licensed to The Apereo Foundation under one or more contributor license
 * agreements. See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.
 *
 *
 * The Apereo Foundation licenses this file to you under the Educational
 * Community License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License
 * at:
 *
 *   http://opensource.org/licenses/ecl2.txt
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations under
 * the License.
 *
 */

package org.opencastproject.series.impl.persistence;

import static org.opencastproject.security.api.SecurityConstants.GLOBAL_ADMIN_ROLE;

import org.opencastproject.metadata.dublincore.DublinCore;
import org.opencastproject.metadata.dublincore.DublinCoreCatalog;
import org.opencastproject.metadata.dublincore.DublinCoreCatalogService;
import org.opencastproject.metadata.dublincore.DublinCoreXmlFormat;
import org.opencastproject.security.api.AccessControlList;
import org.opencastproject.security.api.AccessControlParser;
import org.opencastproject.security.api.AccessControlParsingException;
import org.opencastproject.security.api.AccessControlUtil;
import org.opencastproject.security.api.Organization;
import org.opencastproject.security.api.Permissions;
import org.opencastproject.security.api.SecurityConstants;
import org.opencastproject.security.api.SecurityService;
import org.opencastproject.security.api.UnauthorizedException;
import org.opencastproject.security.api.User;
import org.opencastproject.series.api.Series;
import org.opencastproject.series.impl.SeriesServiceDatabase;
import org.opencastproject.series.impl.SeriesServiceDatabaseException;
import org.opencastproject.util.NotFoundException;

import com.entwinemedia.fn.data.Opt;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import javax.persistence.EntityManager;
import javax.persistence.EntityManagerFactory;
import javax.persistence.EntityTransaction;
import javax.persistence.NoResultException;
import javax.persistence.Query;
import javax.persistence.TypedQuery;

/**
 * Implements {@link SeriesServiceDatabase}. Defines permanent storage for series.
 */
@Component(
    property = {
        "service.description=Series Service"
    },
    immediate = true,
    service = { SeriesServiceDatabase.class }
)
public class SeriesServiceDatabaseImpl implements SeriesServiceDatabase {

  /** Logging utilities */
  private static final Logger logger = LoggerFactory.getLogger(SeriesServiceDatabaseImpl.class);

  /** JPA persistence unit name */
  public static final String PERSISTENCE_UNIT = "org.opencastproject.series.impl.persistence";

  /** Factory used to create {@link EntityManager}s for transactions */
  protected EntityManagerFactory emf;

  /** Dublin core service for serializing and deserializing Dublin cores */
  protected DublinCoreCatalogService dcService;

  /** The security service */
  protected SecurityService securityService;

  /** OSGi DI */
  @Reference(target = "(osgi.unit.name=org.opencastproject.series.impl.persistence)")
  public void setEntityManagerFactory(EntityManagerFactory emf) {
    this.emf = emf;
  }

  /**
   * Creates {@link EntityManagerFactory} using persistence provider and properties passed via OSGi.
   *
   * @param cc
   */
  @Activate
  public void activate(ComponentContext cc) {
    logger.info("Activating persistence manager for series");
  }

  /**
   * OSGi callback to set the security service.
   *
   * @param securityService
   *          the securityService to set
   */
  @Reference
  public void setSecurityService(SecurityService securityService) {
    this.securityService = securityService;
  }

  /**
   * OSGi callback to set dublin core catalog service.
   *
   * @param dcService
   *          {@link DublinCoreCatalogService} object
   */
  @Reference
  public void setDublinCoreService(DublinCoreCatalogService dcService) {
    this.dcService = dcService;
  }

  /**
   * Serializes Dublin core catalog and returns it as String.
   *
   * @param dc
   *          {@link DublinCoreCatalog} to be serialized
   * @return String presenting serialized dublin core
   * @throws IOException
   *           if serialization fails
   */
  private String serializeDublinCore(DublinCoreCatalog dc) throws IOException {
    InputStream in = dcService.serialize(dc);

    StringWriter writer = new StringWriter();
    IOUtils.copy(in, writer, "UTF-8");

    return writer.toString();
  }

  /*
   * (non-Javadoc)
   *
   * @see org.opencastproject.series.impl.SeriesServiceDatabase#deleteSeries(java.lang.String)
   */
  @Override
  public void deleteSeries(String seriesId) throws SeriesServiceDatabaseException, NotFoundException {
    EntityManager em = emf.createEntityManager();
    EntityTransaction tx = em.getTransaction();
    try {
      tx.begin();
      SeriesEntity entity = getSeriesEntity(seriesId, em);
      if (entity == null) {
        throw new NotFoundException("Series with ID " + seriesId + " does not exist");
      }
      // Ensure this user is allowed to delete this series
      String accessControlXml = entity.getAccessControl();
      if (accessControlXml != null) {
        AccessControlList acl = AccessControlParser.parseAcl(accessControlXml);
        User currentUser = securityService.getUser();
        Organization currentOrg = securityService.getOrganization();
        if (!AccessControlUtil.isAuthorized(acl, currentUser, currentOrg, Permissions.Action.WRITE.toString())) {
          throw new UnauthorizedException(currentUser + " is not authorized to update series " + seriesId);
        }
      }

      Date now = new Date();
      entity.setModifiedDate(now);
      entity.setDeletionDate(now);
      em.merge(entity);
      tx.commit();
    } catch (NotFoundException e) {
      throw e;
    } catch (Exception e) {
      logger.error("Could not delete series", e);
      if (tx.isActive()) {
        tx.rollback();
      }
      throw new SeriesServiceDatabaseException(e);
    } finally {
      em.close();
    }
  }

  /*
   * (non-Javadoc)
   *
   * @see org.opencastproject.series.impl.SeriesServiceDatabase#deleteSeriesProperty(java.lang.String)
   */
  @Override
  public void deleteSeriesProperty(String seriesId, String propertyName)
          throws SeriesServiceDatabaseException, NotFoundException {
    EntityManager em = emf.createEntityManager();
    EntityTransaction tx = em.getTransaction();
    try {
      tx.begin();
      SeriesEntity entity = getSeriesEntity(seriesId, em);
      if (entity == null) {
        throw new NotFoundException("Series with ID " + seriesId + " does not exist");
      }
      Map<String, String> properties = entity.getProperties();
      String propertyValue = properties.get(propertyName);
      if (propertyValue == null) {
        throw new NotFoundException(
                "Series with ID " + seriesId + " doesn't have a property with name '" + propertyName + "'");
      }

      if (!userHasWriteAccess(entity)) {
        throw new UnauthorizedException(securityService.getUser() + " is not authorized to delete series " + seriesId
                + " property " + propertyName);
      }

      properties.remove(propertyName);
      entity.setProperties(properties);
      entity.setModifiedDate(new Date());
      em.merge(entity);
      tx.commit();
    } catch (NotFoundException e) {
      throw e;
    } catch (Exception e) {
      logger.error("Could not delete property for series '{}'", seriesId, e);
      if (tx.isActive()) {
        tx.rollback();
      }
      throw new SeriesServiceDatabaseException(e);
    } finally {
      em.close();
    }
  }

  /*
   * (non-Javadoc)
   *
   * @see org.opencastproject.series.impl.SeriesServiceDatabase#getAllSeries()
   */
  @SuppressWarnings("unchecked")
  @Override
  public List<SeriesEntity> getAllSeries() throws SeriesServiceDatabaseException {
    EntityManager em = emf.createEntityManager();
    Query query = em.createNamedQuery("Series.findAll");
    try {
      return query.getResultList();
    } catch (Exception e) {
      logger.error("Could not retrieve all series", e);
      throw new SeriesServiceDatabaseException(e);
    } finally {
      em.close();
    }
  }

  /*
   * (non-Javadoc)
   *
   * @see org.opencastproject.series.impl.SeriesServiceDatabase#getAccessControlList(java.lang.String)
   */
  @Override
  public AccessControlList getAccessControlList(String seriesId)
          throws NotFoundException, SeriesServiceDatabaseException {
    EntityManager em = emf.createEntityManager();
    try {
      SeriesEntity entity = getSeriesEntity(seriesId, em);
      if (entity == null) {
        throw new NotFoundException("Could not found series with ID " + seriesId);
      }
      if (entity.getAccessControl() == null) {
        return null;
      } else {
        return AccessControlParser.parseAcl(entity.getAccessControl());
      }
    } catch (NotFoundException e) {
      throw e;
    } catch (Exception e) {
      logger.error("Could not retrieve ACL for series '{}'", seriesId, e);
      throw new SeriesServiceDatabaseException(e);
    } finally {
      em.close();
    }
  }

  /*
   * (non-Javadoc)
   *
   * @see org.opencastproject.series.impl.SeriesServiceDatabase#storeSeries(org.opencastproject.metadata.dublincore.
   * DublinCoreCatalog)
   */
  @Override
  public DublinCoreCatalog storeSeries(DublinCoreCatalog dc)
          throws SeriesServiceDatabaseException, UnauthorizedException {
    if (dc == null) {
      throw new SeriesServiceDatabaseException("Invalid value for Dublin core catalog: null");
    }
    String seriesId = dc.getFirst(DublinCore.PROPERTY_IDENTIFIER);
    String seriesXML;
    try {
      seriesXML = serializeDublinCore(dc);
    } catch (Exception e1) {
      logger.error("Could not serialize Dublin Core: {}", e1);
      throw new SeriesServiceDatabaseException(e1);
    }
    EntityManager em = emf.createEntityManager();
    EntityTransaction tx = em.getTransaction();
    DublinCoreCatalog newSeries = null;
    try {
      tx.begin();
      SeriesEntity entity = getPotentiallyDeletedSeriesEntity(seriesId, em);
      if (entity == null || entity.isDeleted()) {
        // If the series existed but is marked deleted, we completely delete it
        // here to make sure no remains of the old series linger.
        if (entity != null) {
          this.deleteSeries(seriesId);
        }

        // no series stored, create new entity
        entity = new SeriesEntity();
        entity.setOrganization(securityService.getOrganization().getId());
        entity.setSeriesId(seriesId);
        entity.setSeries(seriesXML);
        entity.setModifiedDate(new Date());
        em.persist(entity);
        newSeries = dc;
      } else {
        // Ensure this user is allowed to update this series
        String accessControlXml = entity.getAccessControl();
        if (accessControlXml != null) {
          AccessControlList acl = AccessControlParser.parseAcl(accessControlXml);
          User currentUser = securityService.getUser();
          Organization currentOrg = securityService.getOrganization();
          if (!AccessControlUtil.isAuthorized(acl, currentUser, currentOrg, Permissions.Action.WRITE.toString())) {
            throw new UnauthorizedException(currentUser + " is not authorized to update series " + seriesId);
          }
        }
        entity.setSeries(seriesXML);
        entity.setModifiedDate(new Date());
        em.merge(entity);
      }
      tx.commit();
      return newSeries;
    } catch (Exception e) {
      logger.error("Could not update series", e);
      if (tx.isActive()) {
        tx.rollback();
      }
      throw new SeriesServiceDatabaseException(e);
    } finally {
      em.close();
    }

  }

  /**
   * {@inheritDoc}
   *
   * @see org.opencastproject.series.impl.SeriesServiceDatabase#getSeries(java.lang.String)
   */
  @Override
  public DublinCoreCatalog getSeries(String seriesId) throws NotFoundException, SeriesServiceDatabaseException {
    EntityManager em = emf.createEntityManager();
    EntityTransaction tx = em.getTransaction();
    try {
      tx.begin();
      SeriesEntity entity = getSeriesEntity(seriesId, em);
      if (entity == null) {
        throw new NotFoundException("No series with id=" + seriesId + " exists");
      }
      // Ensure this user is allowed to read this series
      String accessControlXml = entity.getAccessControl();
      if (accessControlXml != null) {
        AccessControlList acl = AccessControlParser.parseAcl(accessControlXml);
        User currentUser = securityService.getUser();
        Organization currentOrg = securityService.getOrganization();
        // There are several reasons a user may need to load a series: to read content, to edit it, or add content
        if (!AccessControlUtil.isAuthorized(acl, currentUser, currentOrg, Permissions.Action.READ.toString())
                && !AccessControlUtil.isAuthorized(acl, currentUser, currentOrg,
                        Permissions.Action.CONTRIBUTE.toString())
                && !AccessControlUtil.isAuthorized(acl, currentUser, currentOrg, Permissions.Action.WRITE.toString())) {
          throw new UnauthorizedException(currentUser + " is not authorized to see series " + seriesId);
        }
      }
      return dcService.load(IOUtils.toInputStream(entity.getDublinCoreXML(), "UTF-8"));
    } catch (NotFoundException e) {
      throw e;
    } catch (Exception e) {
      logger.error("Could not retrieve series with ID '{}'", seriesId, e);
      if (tx.isActive()) {
        tx.rollback();
      }
      throw new SeriesServiceDatabaseException(e);
    } finally {
      em.close();
    }
  }

  @Override
  public List<Series> getAllForAdministrativeRead(Date from, Optional<Date> to, int limit)
          throws SeriesServiceDatabaseException, UnauthorizedException {
    // Validate parameters
    if (limit <= 0) {
      throw new IllegalArgumentException("limit has to be > 0");
    }

    // Make sure the user is actually an administrator of sorts
    User user = securityService.getUser();
    if (!user.hasRole(GLOBAL_ADMIN_ROLE) && !user.hasRole(user.getOrganization().getAdminRole())) {
      throw new UnauthorizedException(user, getClass().getName() + ".getModifiedInRangeForAdministrativeRead");
    }

    // Load series from DB.
    EntityManager em = emf.createEntityManager();
    try {
      TypedQuery<SeriesEntity> q;
      if (to.isPresent()) {
        if (from.after(to.get())) {
          throw new IllegalArgumentException("`from` is after `to`");
        }

        q = em.createNamedQuery("Series.getAllModifiedInRange", SeriesEntity.class)
            .setParameter("from", from)
            .setParameter("to", to.get())
            .setParameter("organization", user.getOrganization().getId())
            .setMaxResults(limit);
      } else {
        q = em.createNamedQuery("Series.getAllModifiedSince", SeriesEntity.class)
            .setParameter("since", from)
            .setParameter("organization", user.getOrganization().getId())
            .setMaxResults(limit);
      }

      final List<Series> out = new ArrayList<>();
      for (SeriesEntity entity : q.getResultList()) {
        final Series series = new Series();
        series.setId(entity.getSeriesId());
        series.setOrganization(entity.getOrganization());
        series.setDublinCore(DublinCoreXmlFormat.read(entity.getDublinCoreXML()));
        series.setAccessControl(entity.getAccessControl());
        series.setModifiedDate(entity.getModifiedDate());
        series.setDeletionDate(entity.getDeletionDate());
        out.add(series);
      }

      return out;
    } catch (Exception e) {
      String msg = String.format("Could not retrieve series modified between '%s' and '%s'", from, to);
      throw new SeriesServiceDatabaseException(msg, e);
    } finally {
      em.close();
    }
  }

  /**
   * {@inheritDoc}
   *
   * @see org.opencastproject.series.impl.SeriesServiceDatabase#getSeriesProperties(java.lang.String)
   */
  @Override
  public Map<String, String> getSeriesProperties(String seriesId)
          throws NotFoundException, SeriesServiceDatabaseException {
    EntityManager em = emf.createEntityManager();
    EntityTransaction tx = em.getTransaction();
    try {
      tx.begin();
      SeriesEntity entity = getSeriesEntity(seriesId, em);
      if (entity == null) {
        throw new NotFoundException("No series with id=" + seriesId + " exists");
      }
      if (!userHasReadAccess(entity)) {
        throw new UnauthorizedException(
                securityService.getUser() + " is not authorized to see series " + seriesId + " properties");
      }
      return entity.getProperties();
    } catch (NotFoundException e) {
      throw e;
    } catch (Exception e) {
      logger.error("Could not retrieve properties of series '{}'", seriesId, e);
      if (tx.isActive()) {
        tx.rollback();
      }
      throw new SeriesServiceDatabaseException(e);
    } finally {
      em.close();
    }
  }

  /**
   * {@inheritDoc}
   *
   * @see org.opencastproject.series.impl.SeriesServiceDatabase#getSeriesProperty(java.lang.String, java.lang.String)
   */
  @Override
  public String getSeriesProperty(String seriesId, String propertyName)
          throws NotFoundException, SeriesServiceDatabaseException {
    EntityManager em = emf.createEntityManager();
    EntityTransaction tx = em.getTransaction();
    try {
      tx.begin();
      SeriesEntity entity = getSeriesEntity(seriesId, em);
      if (entity == null) {
        throw new NotFoundException("No series with id=" + seriesId + " exists");
      }
      if (!userHasReadAccess(entity)) {
        throw new UnauthorizedException(
                securityService.getUser() + " is not authorized to see series " + seriesId + " properties");
      }
      if (entity.getProperties() == null || StringUtils.isBlank(entity.getProperties().get(propertyName))) {
        throw new NotFoundException(
                "No series property for series with id=" + seriesId + " and property name " + propertyName);
      }
      return entity.getProperties().get(propertyName);
    } catch (NotFoundException e) {
      throw e;
    } catch (Exception e) {
      logger.error("Could not retrieve property '{}' of series '{}'", propertyName, seriesId, e);
      if (tx.isActive()) {
        tx.rollback();
      }
      throw new SeriesServiceDatabaseException(e);
    } finally {
      em.close();
    }
  }

  private boolean userHasWriteAccess(SeriesEntity entity) throws IOException, AccessControlParsingException {
    // Ensure this user is allowed to write this series
    String accessControlXml = entity.getAccessControl();
    if (accessControlXml != null) {
      AccessControlList acl = AccessControlParser.parseAcl(accessControlXml);
      User currentUser = securityService.getUser();
      Organization currentOrg = securityService.getOrganization();
      if (!AccessControlUtil.isAuthorized(acl, currentUser, currentOrg, Permissions.Action.WRITE.toString())) {
        return false;
      }
    }
    return true;
  }

  private boolean userHasReadAccess(SeriesEntity entity) throws IOException, AccessControlParsingException {
    // Ensure this user is allowed to read this series
    String accessControlXml = entity.getAccessControl();
    if (accessControlXml != null) {
      AccessControlList acl = AccessControlParser.parseAcl(accessControlXml);
      User currentUser = securityService.getUser();
      Organization currentOrg = securityService.getOrganization();

      if (currentUser.hasRole(SecurityConstants.GLOBAL_CAPTURE_AGENT_ROLE)) {
        return true;
      }
      // There are several reasons a user may need to load a series: to read content, to edit it, or add content
      if (!AccessControlUtil.isAuthorized(acl, currentUser, currentOrg, Permissions.Action.READ.toString())
              && !AccessControlUtil.isAuthorized(acl, currentUser, currentOrg, Permissions.Action.CONTRIBUTE.toString())
              && !AccessControlUtil.isAuthorized(acl, currentUser, currentOrg, Permissions.Action.WRITE.toString())) {
        return false;
      }
    }
    return true;
  }

  /*
   * (non-Javadoc)
   *
   * @see org.opencastproject.series.impl.SeriesServiceDatabase#storeSeriesAccessControl(java.lang.String,
   * org.opencastproject.security.api.AccessControlList)
   */
  @Override
  public boolean storeSeriesAccessControl(String seriesId, AccessControlList accessControl)
          throws NotFoundException, SeriesServiceDatabaseException {
    if (accessControl == null) {
      logger.error("Access control parameter is <null> for series '{}'", seriesId);
      throw new IllegalArgumentException("Argument for updating ACL for series " + seriesId + " is null");
    }

    String serializedAC;
    try {
      serializedAC = AccessControlParser.toXml(accessControl);
    } catch (Exception e) {
      logger.error("Could not serialize access control parameter", e);
      throw new SeriesServiceDatabaseException(e);
    }
    EntityManager em = emf.createEntityManager();
    EntityTransaction tx = em.getTransaction();
    boolean updated = false;
    try {
      tx.begin();
      SeriesEntity entity = getSeriesEntity(seriesId, em);
      if (entity == null) {
        throw new NotFoundException("Series with ID " + seriesId + " does not exist.");
      }
      if (entity.getAccessControl() != null) {
        // Ensure this user is allowed to update this series
        String accessControlXml = entity.getAccessControl();
        if (accessControlXml != null) {
          AccessControlList acl = AccessControlParser.parseAcl(accessControlXml);
          User currentUser = securityService.getUser();
          Organization currentOrg = securityService.getOrganization();
          if (!AccessControlUtil.isAuthorized(acl, currentUser, currentOrg, Permissions.Action.WRITE.toString())) {
            throw new UnauthorizedException(currentUser + " is not authorized to update ACLs on series " + seriesId);
          }
        }
        updated = true;
      }
      entity.setAccessControl(serializedAC);
      entity.setModifiedDate(new Date());
      em.merge(entity);
      tx.commit();
      return updated;
    } catch (NotFoundException e) {
      throw e;
    } catch (Exception e) {
      logger.error("Could not store ACL for series '{}'", seriesId, e);
      if (tx.isActive()) {
        tx.rollback();
      }
      throw new SeriesServiceDatabaseException(e);
    } finally {
      em.close();
    }
  }

  @Override
  public int countSeries() throws SeriesServiceDatabaseException {
    EntityManager em = emf.createEntityManager();
    Query query = em.createNamedQuery("Series.getCount");
    try {
      Long total = (Long) query.getSingleResult();
      return total.intValue();
    } catch (Exception e) {
      logger.error("Could not find number of series.", e);
      throw new SeriesServiceDatabaseException(e);
    } finally {
      em.close();
    }
  }

  @Override
  public void updateSeriesProperty(String seriesId, String propertyName, String propertyValue)
          throws NotFoundException, SeriesServiceDatabaseException {
    EntityManager em = null;
    EntityTransaction tx = null;
    try {
      em = emf.createEntityManager();
      tx = em.getTransaction();
      tx.begin();
      SeriesEntity entity = getSeriesEntity(seriesId, em);
      if (entity == null) {
        throw new NotFoundException("Series with ID " + seriesId + " doesn't exist");
      }

      if (!userHasWriteAccess(entity)) {
        throw new UnauthorizedException(securityService.getUser() + " is not authorized to update series " + seriesId
                + " property " + propertyName + " to " + propertyValue);
      }

      Map<String, String> properties = entity.getProperties();
      properties.put(propertyName, propertyValue);
      entity.setProperties(properties);
      entity.setModifiedDate(new Date());
      em.merge(entity);
      tx.commit();
    } catch (NotFoundException e) {
      throw e;
    } catch (Exception e) {
      if (tx.isActive()) {
        tx.rollback();
      }
      logger.error("Couldn't update series {} with property: {}:{} because", seriesId, propertyName, propertyValue, e);
      throw new SeriesServiceDatabaseException(e);
    } finally {
      if (em != null) {
        em.close();
      }
    }
  }

  /**
   * Gets a series by its ID, using the current organizational context.
   *
   * @param id
   *          the series identifier
   * @param em
   *          an open entity manager
   * @return the series entity, or null if not found or if the series is deleted.
   */
  protected SeriesEntity getSeriesEntity(String id, EntityManager em) {
    SeriesEntity entity = getPotentiallyDeletedSeriesEntity(id, em);
    return entity == null || entity.isDeleted() ? null : entity;
  }

  /**
   * Gets a potentially deleted series by its ID, using the current organizational context.
   *
   * @param id
   *          the series identifier
   * @param em
   *          an open entity manager
   * @return the series entity, or null if not found
   */
  protected SeriesEntity getPotentiallyDeletedSeriesEntity(String id, EntityManager em) {
    String orgId = securityService.getOrganization().getId();
    Query q = em.createNamedQuery("seriesById").setParameter("seriesId", id).setParameter("organization", orgId);
    try {
      return (SeriesEntity) q.getSingleResult();
    } catch (NoResultException e) {
      return null;
    }
  }

  @Override
  public boolean storeSeriesElement(String seriesId, String type, byte[] data) throws SeriesServiceDatabaseException {
    EntityManager em = null;
    EntityTransaction tx = null;
    final boolean success;
    try {
      em = emf.createEntityManager();
      tx = em.getTransaction();
      tx.begin();
      SeriesEntity series = getSeriesEntity(seriesId, em);
      if (series == null) {
        success = false;
      } else {
        series.addElement(type, data);
        series.setModifiedDate(new Date());
        em.merge(series);
        tx.commit();
        success = true;
      }
    } catch (Exception e) {
      if (tx.isActive()) {
        tx.rollback();
      }
      throw new SeriesServiceDatabaseException(e);
    } finally {
      if (em != null) {
        em.close();
      }
    }
    return success;
  }

  @Override
  public boolean deleteSeriesElement(String seriesId, String type) throws SeriesServiceDatabaseException {
    final boolean success;
    EntityManager em = null;
    EntityTransaction tx = null;
    try {
      em = emf.createEntityManager();
      tx = em.getTransaction();
      tx.begin();
      SeriesEntity series = getSeriesEntity(seriesId, em);
      if (series == null) {
        success = false;
      } else {
        if (series.getElements().containsKey(type)) {
          series.removeElement(type);
          series.setModifiedDate(new Date());
          em.merge(series);
          tx.commit();
          success = true;
        } else {
          success = false;
        }
      }
    } catch (Exception e) {
      if (tx.isActive()) {
        tx.rollback();
      }
      throw new SeriesServiceDatabaseException(e);
    } finally {
      if (em != null) {
        em.close();
      }
    }
    return success;
  }

  @Override
  public Opt<byte[]> getSeriesElement(String seriesId, String type) throws SeriesServiceDatabaseException {
    final Opt<byte[]> data;
    EntityManager em = null;
    try {
      em = emf.createEntityManager();
      SeriesEntity series = getSeriesEntity(seriesId, em);
      if (series == null) {
        data = Opt.none();
      } else {
        Map<String, byte[]> elements = series.getElements();
        if (elements.containsKey(type)) {
          data = Opt.some(elements.get(type));
        } else {
          data = Opt.none();
        }
      }
    } catch (Exception e) {
      throw new SeriesServiceDatabaseException(e);
    } finally {
      if (em != null) {
        em.close();
      }
    }
    return data;
  }

  @Override
  public Opt<Map<String, byte[]>> getSeriesElements(String seriesId) throws SeriesServiceDatabaseException {
    final Opt<Map<String, byte[]>> elements;
    EntityManager em = null;
    try {
      em = emf.createEntityManager();
      SeriesEntity series = getSeriesEntity(seriesId, em);
      if (series == null) {
        elements = Opt.none();
      } else {
        elements = Opt.some(series.getElements());
      }
    } catch (Exception e) {
      throw new SeriesServiceDatabaseException(e);
    } finally {
      if (em != null) {
        em.close();
      }
    }
    return elements;
  }

  @Override
  public boolean existsSeriesElement(String seriesId, String type) throws SeriesServiceDatabaseException {
    return (getSeriesElement(seriesId, type).isSome()) ? true : false;
  }
}
