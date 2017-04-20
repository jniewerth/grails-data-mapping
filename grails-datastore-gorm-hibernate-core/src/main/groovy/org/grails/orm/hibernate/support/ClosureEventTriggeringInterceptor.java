/*
 * Copyright 2003-2007 the original author or authors.
 *
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
 */
package org.grails.orm.hibernate.support;


import org.grails.datastore.gorm.events.ConfigurableApplicationContextEventPublisher;
import org.grails.datastore.gorm.events.ConfigurableApplicationEventPublisher;
import org.grails.datastore.mapping.dirty.checking.DirtyCheckable;
import org.grails.datastore.mapping.engine.event.AbstractPersistenceEvent;
import org.grails.datastore.mapping.engine.ModificationTrackingEntityAccess;
import org.grails.datastore.mapping.model.MappingContext;
import org.grails.datastore.mapping.model.PersistentEntity;
import org.grails.orm.hibernate.AbstractHibernateDatastore;
import org.grails.orm.hibernate.AbstractHibernateGormInstanceApi;
import org.hibernate.Hibernate;
import org.hibernate.HibernateException;
import org.hibernate.engine.internal.Nullability;
import org.hibernate.event.service.spi.EventListenerRegistry;
import org.hibernate.event.spi.*;
import org.hibernate.persister.entity.EntityPersister;
import org.springframework.beans.BeansException;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * Listens for Hibernate events and publishes corresponding Datastore events.
 *
 * @author Graeme Rocher
 * @author Lari Hotari
 * @author Burt Beckwith
 * @since 1.0
 */
public class ClosureEventTriggeringInterceptor extends AbstractClosureEventTriggeringInterceptor {

    //    private final Logger log = LoggerFactory.getLogger(getClass());
    private static final long serialVersionUID = 1;

    /**
     * @deprecated Use {@link AbstractPersistenceEvent#ONLOAD_EVENT} instead
     */
    public static final String ONLOAD_EVENT = AbstractPersistenceEvent.ONLOAD_EVENT;
    /**
     * @deprecated Use {@link AbstractPersistenceEvent#ONLOAD_SAVE} instead
     */
    public static final String ONLOAD_SAVE = AbstractPersistenceEvent.ONLOAD_SAVE;
    /**
     * @deprecated Use {@link AbstractPersistenceEvent#BEFORE_LOAD_EVENT} instead
     */
    public static final String BEFORE_LOAD_EVENT = AbstractPersistenceEvent.BEFORE_LOAD_EVENT;
    /**
     * @deprecated Use {@link AbstractPersistenceEvent#BEFORE_INSERT_EVENT} instead
     */
    public static final String BEFORE_INSERT_EVENT = AbstractPersistenceEvent.BEFORE_INSERT_EVENT;
    /**
     * @deprecated Use {@link AbstractPersistenceEvent#AFTER_INSERT_EVENT} instead
     */
    public static final String AFTER_INSERT_EVENT = AbstractPersistenceEvent.AFTER_INSERT_EVENT;
    /**
     * @deprecated Use {@link AbstractPersistenceEvent#BEFORE_UPDATE_EVENT} instead
     */
    public static final String BEFORE_UPDATE_EVENT = AbstractPersistenceEvent.BEFORE_UPDATE_EVENT;
    /**
     * @deprecated Use {@link AbstractPersistenceEvent#AFTER_UPDATE_EVENT} instead
     */
    public static final String AFTER_UPDATE_EVENT = AbstractPersistenceEvent.AFTER_UPDATE_EVENT;
    /**
     * @deprecated Use {@link AbstractPersistenceEvent#BEFORE_DELETE_EVENT} instead
     */
    public static final String BEFORE_DELETE_EVENT = AbstractPersistenceEvent.BEFORE_DELETE_EVENT;
    /**
     * @deprecated Use {@link AbstractPersistenceEvent#AFTER_DELETE_EVENT} instead
     */
    public static final String AFTER_DELETE_EVENT = AbstractPersistenceEvent.AFTER_DELETE_EVENT;
    /**
     * @deprecated Use {@link AbstractPersistenceEvent#AFTER_LOAD_EVENT} instead
     */
    public static final String AFTER_LOAD_EVENT = AbstractPersistenceEvent.AFTER_LOAD_EVENT;

    protected AbstractHibernateDatastore datastore;
    protected ConfigurableApplicationEventPublisher eventPublisher;

    public void setDatastore(AbstractHibernateDatastore datastore) {
        this.datastore = datastore;
    }

    public void setEventPublisher(ConfigurableApplicationEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    @Override
    public void onSaveOrUpdate(SaveOrUpdateEvent hibernateEvent) throws HibernateException {
        Object entity = getEntity(hibernateEvent);
        if(entity != null && datastore.getMappingContext().getProxyHandler().isInitialized(entity)) {
            activateDirtyChecking(entity);
            org.grails.datastore.mapping.engine.event.SaveOrUpdateEvent grailsEvent = new org.grails.datastore.mapping.engine.event.SaveOrUpdateEvent(
                    this.datastore, entity);
            publishEvent(hibernateEvent, grailsEvent);
        }
        super.onSaveOrUpdate(hibernateEvent);
    }

    protected Object getEntity(SaveOrUpdateEvent hibernateEvent) {
        Object object = hibernateEvent.getObject();
        if(object != null) {
            return object;
        }
        else {
            return hibernateEvent.getEntity();
        }
    }

    public void onPreLoad(PreLoadEvent hibernateEvent) {
        org.grails.datastore.mapping.engine.event.PreLoadEvent grailsEvent = new org.grails.datastore.mapping.engine.event.PreLoadEvent(
                this.datastore, hibernateEvent.getEntity());
        publishEvent(hibernateEvent, grailsEvent);
    }

    public void onPostLoad(PostLoadEvent hibernateEvent) {
        Object entity = hibernateEvent.getEntity();
        activateDirtyChecking(entity);
        publishEvent(hibernateEvent, new org.grails.datastore.mapping.engine.event.PostLoadEvent(
                this.datastore, entity));
    }

    public boolean onPreInsert(PreInsertEvent hibernateEvent) {
        Object entity = hibernateEvent.getEntity();
        Class type = Hibernate.getClass(entity);
        MappingContext mappingContext = datastore.getMappingContext();
        PersistentEntity persistentEntity = mappingContext.getPersistentEntity(type.getName());
        AbstractPersistenceEvent grailsEvent;
        ModificationTrackingEntityAccess entityAccess = null;
        if(persistentEntity != null) {
            entityAccess = new ModificationTrackingEntityAccess(mappingContext.createEntityAccess(persistentEntity, entity));
            grailsEvent = new org.grails.datastore.mapping.engine.event.PreInsertEvent(this.datastore, persistentEntity, entityAccess);
        }
        else {
            grailsEvent = new org.grails.datastore.mapping.engine.event.PreInsertEvent(this.datastore, entity);
        }

        publishEvent(hibernateEvent, grailsEvent);

        boolean cancelled = grailsEvent.isCancelled();
        if(!cancelled && entityAccess != null) {
            synchronizeHibernateState(hibernateEvent, entityAccess);
        }
        return cancelled;
    }

    private void synchronizeHibernateState(PreInsertEvent hibernateEvent, ModificationTrackingEntityAccess entityAccess) {
        Map<String, Object> modifiedProperties = entityAccess.getModifiedProperties();
        if(!modifiedProperties.isEmpty()) {
            Object[] state = hibernateEvent.getState();
            EntityPersister persister = hibernateEvent.getPersister();
            synchronizeHibernateState(persister, state, modifiedProperties);
        }
    }

    private void synchronizeHibernateState(PreUpdateEvent hibernateEvent, ModificationTrackingEntityAccess entityAccess) {
        Map<String, Object> modifiedProperties = entityAccess.getModifiedProperties();
        if(!modifiedProperties.isEmpty()) {
            Object[] state = hibernateEvent.getState();
            EntityPersister persister = hibernateEvent.getPersister();
            synchronizeHibernateState(persister, state, modifiedProperties);
        }
    }

    private void synchronizeHibernateState(EntityPersister persister, Object[] state, Map<String, Object> modifiedProperties) {
        Set<String> properties = modifiedProperties.keySet();
        Iterator<String> propertyIterator = properties.iterator();
        int[] indexes = HibernateVersionSupport.resolveAttributeIndexes(persister, properties);
        for (int index : indexes) {
            state[index] = modifiedProperties.get(propertyIterator.next());
        }
    }

    public void onPostInsert(PostInsertEvent hibernateEvent) {
        Object entity = hibernateEvent.getEntity();
        org.grails.datastore.mapping.engine.event.PostInsertEvent grailsEvent = new org.grails.datastore.mapping.engine.event.PostInsertEvent(
                this.datastore, entity);
        activateDirtyChecking(entity);
        publishEvent(hibernateEvent, grailsEvent);
    }

    public boolean onPreUpdate(PreUpdateEvent hibernateEvent) {
        Object entity = hibernateEvent.getEntity();
        Class type = Hibernate.getClass(entity);
        MappingContext mappingContext = datastore.getMappingContext();
        PersistentEntity persistentEntity = mappingContext.getPersistentEntity(type.getName());
        AbstractPersistenceEvent grailsEvent;
        ModificationTrackingEntityAccess entityAccess = null;
        if(persistentEntity != null) {
            entityAccess = new ModificationTrackingEntityAccess(mappingContext.createEntityAccess(persistentEntity, entity));
            grailsEvent = new org.grails.datastore.mapping.engine.event.PreUpdateEvent(this.datastore, persistentEntity, entityAccess);
        }
        else {
            grailsEvent = new org.grails.datastore.mapping.engine.event.PreUpdateEvent(this.datastore, entity);
        }

        publishEvent(hibernateEvent, grailsEvent);
        boolean cancelled = grailsEvent.isCancelled();
        if(!cancelled && entityAccess != null) {
            synchronizeHibernateState(hibernateEvent, entityAccess);
        }
        return cancelled;

    }

    public void onPostUpdate(PostUpdateEvent hibernateEvent) {
        Object entity = hibernateEvent.getEntity();
        activateDirtyChecking(entity);
        publishEvent(hibernateEvent, new org.grails.datastore.mapping.engine.event.PostUpdateEvent(
                this.datastore, entity));
    }

    public boolean onPreDelete(PreDeleteEvent hibernateEvent) {
        AbstractPersistenceEvent event = new org.grails.datastore.mapping.engine.event.PreDeleteEvent(
                this.datastore, hibernateEvent.getEntity());
        publishEvent(hibernateEvent, event);
        return event.isCancelled();
    }

    public void onPostDelete(PostDeleteEvent hibernateEvent) {
        org.grails.datastore.mapping.engine.event.PostDeleteEvent grailsEvent = new org.grails.datastore.mapping.engine.event.PostDeleteEvent(
                this.datastore, hibernateEvent.getEntity());
        publishEvent(hibernateEvent, grailsEvent);
    }

    private void publishEvent(AbstractEvent hibernateEvent, AbstractPersistenceEvent mappingEvent) {
        mappingEvent.setNativeEvent(hibernateEvent);
        if(eventPublisher != null) {
            eventPublisher.publishEvent(mappingEvent);
        }
    }

    @Override
    public boolean requiresPostCommitHanding(EntityPersister persister) {
        return false;
    }

    @Deprecated
    public static void addNullabilityCheckerPreInsertEventListener(EventListenerRegistry listenerRegistry) {
        listenerRegistry.getEventListenerGroup(EventType.PRE_INSERT).appendListener(NULLABILITY_CHECKER_INSTANCE);
    }

    private static final PreInsertEventListener NULLABILITY_CHECKER_INSTANCE = new NullabilityCheckerPreInsertEventListener();

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        if(applicationContext instanceof ConfigurableApplicationContext) {

            this.eventPublisher = new ConfigurableApplicationContextEventPublisher((ConfigurableApplicationContext) applicationContext);
        }
    }

    @SuppressWarnings("serial")
    private static class NullabilityCheckerPreInsertEventListener implements PreInsertEventListener {
        public boolean onPreInsert(PreInsertEvent event) {
            new Nullability(event.getSession()).checkNullability(event.getState(), event.getPersister(), false);
            return false;
        }
    }

    /**
     * Prevents hitting the database for an extra check if the row exists in the database.
     *
     * ThreadLocal is used to pass the "insert:true" information to Hibernate.
     *
     */
    @Override
    protected Boolean getAssumedUnsaved() {
        return AbstractHibernateGormInstanceApi.getAssumedUnsaved();
    }

    private void activateDirtyChecking(Object entity) {
        if(entity instanceof DirtyCheckable) {
            DirtyCheckable dirtyCheckable = (DirtyCheckable) entity;
            if(dirtyCheckable.listDirtyPropertyNames().isEmpty()) {
                dirtyCheckable.trackChanges();
            }
        }
    }

}
