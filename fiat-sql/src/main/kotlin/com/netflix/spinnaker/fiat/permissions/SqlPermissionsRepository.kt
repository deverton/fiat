/*
 * Copyright 2021 Expedia, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netflix.spinnaker.fiat.permissions

import com.netflix.spinnaker.fiat.config.UnrestrictedResourceConfig.UNRESTRICTED_USERNAME
import com.fasterxml.jackson.databind.ObjectMapper
import com.github.benmanes.caffeine.cache.Caffeine
import com.netflix.spinnaker.fiat.model.UserPermission
import com.netflix.spinnaker.fiat.model.resources.ResourceType
import com.netflix.spinnaker.fiat.permissions.sql.Tables.Companion.PERMISSION
import com.netflix.spinnaker.fiat.permissions.sql.Tables.Companion.RESOURCE
import com.netflix.spinnaker.fiat.permissions.sql.Tables.Companion.USER
import com.netflix.spinnaker.fiat.permissions.sql.transactional
import com.netflix.spinnaker.fiat.permissions.sql.withRetry
import com.netflix.spinnaker.kork.exceptions.IntegrationException
import com.netflix.spinnaker.kork.sql.config.SqlRetryProperties
import com.netflix.spinnaker.kork.sql.routing.withPool
import org.jooq.*
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Duration
import java.util.*
import java.util.concurrent.atomic.AtomicReference


class SqlPermissionsRepository(
    private val clock: Clock,
    private val objectMapper: ObjectMapper,
    private val jooq: DSLContext,
    private val sqlRetryProperties: SqlRetryProperties,
    private val poolName: String,
    private val resources: List<com.netflix.spinnaker.fiat.model.resources.Resource>
    ) : PermissionsRepository {

    private val unrestrictedPermission = Caffeine.newBuilder()
        .expireAfterAccess(Duration.ofSeconds(10))
        .build(this::reloadUnrestricted)

    private val resourceTypes = resources.associateBy { r -> r.resourceType }.toMap()

    companion object {
        private val log = LoggerFactory.getLogger(SqlPermissionsRepository::class.java)

        private const val NO_UPDATED_AT = 0L

        private val fallbackLastModified = AtomicReference<Long>(null)
    }

    override fun put(permission: UserPermission): PermissionsRepository {
        withPool(poolName) {
            jooq.transactional(sqlRetryProperties.transactions) { ctx ->
                val userId = permission.id
                val now = clock.millis()

                val batch = mutableListOf<Query>()

                // Create or update user
                batch += ctx
                    .insertInto(USER,
                        USER.ID,
                        USER.ADMIN,
                        USER.UPDATED_AT
                    )
                    .values(userId, permission.isAdmin, clock.millis())
                    .onConflict(USER.ID)
                    .doUpdate()
                    .set(mapOf(
                        USER.ADMIN to permission.isAdmin,
                        USER.UPDATED_AT to now,
                    ))

                // Insert or update resources and permissions
                permission.allResources.map { r ->
                    val body = objectMapper.writeValueAsString(r)

                    batch += ctx.insertInto(
                        RESOURCE,
                        RESOURCE.RESOURCE_TYPE,
                        RESOURCE.RESOURCE_NAME,
                        RESOURCE.BODY,
                        RESOURCE.UPDATED_AT
                    )
                        .values(r.resourceType, r.name, body, now)
                        .onConflict(RESOURCE.RESOURCE_TYPE, RESOURCE.RESOURCE_NAME)
                        .doUpdate()
                        .set(mapOf(
                            RESOURCE.BODY to body,
                            RESOURCE.UPDATED_AT to now
                        ))

                    batch += ctx.insertInto(
                        PERMISSION,
                        PERMISSION.USER_ID,
                        PERMISSION.RESOURCE_TYPE,
                        PERMISSION.RESOURCE_NAME,
                        PERMISSION.UPDATED_AT
                    )
                        .values(userId, r.resourceType, r.name, now)
                        .onConflict(PERMISSION.USER_ID, PERMISSION.RESOURCE_TYPE, PERMISSION.RESOURCE_NAME)
                        .doUpdate()
                        .set(mapOf(
                            PERMISSION.UPDATED_AT to now
                        ))
                }

                // Delete stale permissions
                batch += ctx
                    .deleteFrom(PERMISSION)
                    .where(
                        PERMISSION.USER_ID.eq(userId).and(
                            PERMISSION.UPDATED_AT.lessThan(now)
                        )
                    )

                ctx.batch(batch).execute()
            }
        }

        return this
    }

    override fun putAllById(permissions: Map<String, UserPermission>?) {
        if (permissions == null || permissions.isEmpty()) {
            return
        }

        // Build a list of all the resources
        val allResources = permissions.values
            .map { it.getAllResources() }
            .flatten()

        val now = clock.millis()

        withPool(poolName) {

            // insert/update resources
            jooq.transactional(sqlRetryProperties.transactions) { ctx ->
                val batch = mutableListOf<Query>()

                allResources.forEach { r ->
                    val body = objectMapper.writeValueAsString(r)

                    batch += ctx.insertInto(RESOURCE)
                        .set(RESOURCE.RESOURCE_TYPE, r.resourceType)
                        .set(RESOURCE.RESOURCE_NAME, r.name)
                        .set(RESOURCE.UPDATED_AT, now)
                        .set(RESOURCE.BODY, body)
                        .onConflict(RESOURCE.RESOURCE_TYPE, RESOURCE.RESOURCE_NAME)
                        .doUpdate()
                        .set(
                            mapOf(
                                RESOURCE.BODY to body,
                                RESOURCE.UPDATED_AT to now
                            )
                        )
                }

                ctx.batch(batch).execute()
            }

            // insert/update users and permissions
            permissions.values.forEach { p ->
                // transaction per-user to avoid locking too long
                jooq.transactional(sqlRetryProperties.transactions) { ctx ->
                    val batch = mutableListOf<Query>()

                    batch += ctx.insertInto(USER)
                        .set(USER.ID, p.id)
                        .set(USER.ADMIN, p.isAdmin)
                        .set(USER.UPDATED_AT, now)
                        .onConflict(USER.ID)
                        .doUpdate()
                        .set(
                            mapOf(
                                USER.ADMIN to p.isAdmin,
                                USER.UPDATED_AT to now
                            )
                        )

                    p.allResources.forEach { r ->
                        batch += ctx.insertInto(PERMISSION)
                            .set(PERMISSION.USER_ID, p.id)
                            .set(PERMISSION.RESOURCE_TYPE, r.resourceType)
                            .set(PERMISSION.RESOURCE_NAME, r.name)
                            .set(PERMISSION.UPDATED_AT, now)
                            .onConflict(PERMISSION.USER_ID, PERMISSION.RESOURCE_TYPE, PERMISSION.RESOURCE_NAME)
                            .doUpdate()
                            .set(
                                mapOf(
                                    PERMISSION.UPDATED_AT to now
                                )
                            )
                    }

                    ctx.batch(batch).execute()
                }
            }



            // Tidy up orphan values
            jooq.transactional(sqlRetryProperties.transactions) { ctx ->
                val batch = mutableListOf<Query>()

                batch += ctx.deleteFrom(PERMISSION).where(PERMISSION.UPDATED_AT.lessThan(now))
                batch += ctx.deleteFrom(USER).where(USER.UPDATED_AT.lessThan(now))
                batch += ctx.deleteFrom(RESOURCE).where(RESOURCE.UPDATED_AT.lessThan(now))

                ctx.batch(batch).execute()
            }
        }
    }

    override fun get(id: String): Optional<UserPermission> {
        if (UNRESTRICTED_USERNAME == id) {
            return Optional.of(getUnrestrictedUserPermission())
        }
        return getFromDatabase(id)
    }

    override fun getAllById(): Map<String, UserPermission> {
        return getAllByRoles(null)
    }

    override fun getAllByRoles(anyRoles: List<String>?): Map<String, UserPermission> {
        // If the role list is null, return every user
        // If the role list is empty, return the unrestricted user
        // Otherwise, return the users with the list of roles

        val unrestrictedUser = getUnrestrictedUserPermission()

        val result = mutableMapOf<String, UserPermission>()

        if (anyRoles != null) {
            result[UNRESTRICTED_USERNAME] = unrestrictedUser
            if (anyRoles.isEmpty()) {
                return result
            }
        }

        return withPool(poolName) {
            jooq.withRetry(sqlRetryProperties.reads) { ctx ->
                var resourceQuery = ctx
                    .selectDistinct(RESOURCE.RESOURCE_TYPE, RESOURCE.RESOURCE_NAME, RESOURCE.BODY)
                    .from(RESOURCE)
                    .let {
                        if (anyRoles != null) {
                            it.join(PERMISSION)
                                .on(PERMISSION.RESOURCE_TYPE.eq(RESOURCE.RESOURCE_TYPE).and(
                                    PERMISSION.RESOURCE_NAME.eq(RESOURCE.RESOURCE_NAME)
                                ))
                                .where(PERMISSION.USER_ID.`in`(
                                    ctx.selectDistinct(USER.ID)
                                        .from(USER)
                                        .join(PERMISSION)
                                        .on(USER.ID.eq(PERMISSION.USER_ID))
                                        .where(PERMISSION.RESOURCE_TYPE.eq(ResourceType.ROLE).and(
                                            PERMISSION.RESOURCE_NAME.`in`(anyRoles)
                                        ))
                                ))
                        }
                        it
                    }

                val existingResources = resourceQuery
                    .groupBy(RESOURCE.RESOURCE_TYPE, RESOURCE.RESOURCE_NAME)
                    .fetch()
                    .intoGroups(RESOURCE.RESOURCE_TYPE)
                    .mapValues { e ->
                        e.value.intoMap(RESOURCE.RESOURCE_NAME).mapValues { v ->
                            objectMapper.readValue(v.value.get(RESOURCE.BODY), resourceTypes[e.key]!!.javaClass)
                        }
                    }

                // Read in all the users with the role and combine with resources
                ctx.select(USER.ID, USER.ADMIN, PERMISSION.RESOURCE_TYPE, PERMISSION.RESOURCE_NAME)
                    .from(USER)
                    .leftJoin(PERMISSION)
                    .on(USER.ID.eq(PERMISSION.USER_ID))
                    .let {
                        if (anyRoles != null) {
                            it.where(PERMISSION.USER_ID.`in`(
                                    ctx.selectDistinct(USER.ID)
                                        .from(USER)
                                        .join(PERMISSION)
                                        .on(USER.ID.eq(PERMISSION.USER_ID))
                                        .where(PERMISSION.RESOURCE_TYPE.eq(ResourceType.ROLE).and(
                                            PERMISSION.RESOURCE_NAME.`in`(anyRoles)
                                        ))
                                )
                            )
                        }
                        it
                    }
                    .fetch()
                    .groupingBy { r -> r.get(USER.ID) }
                    .foldTo (
                        result,
                        { k, e -> UserPermission().setId(k).setAdmin(e.get(USER.ADMIN)).merge(unrestrictedUser) },
                        { _, acc, e ->
                            val resourcesForType = existingResources.getOrDefault(e.get(PERMISSION.RESOURCE_TYPE), emptyMap())
                            val resource = resourcesForType[e.get(PERMISSION.RESOURCE_NAME)]
                            if (resource != null) {
                                acc.addResource(resource)
                            }
                            acc
                        }
                    )
            }
        }
    }

    override fun remove(id: String) {
        withPool(poolName) {
            jooq.transactional(sqlRetryProperties.transactions) { ctx ->
                // Delete permissions
                jooq.delete(PERMISSION)
                    .where(PERMISSION.USER_ID.eq(id))
                    .execute()

                // Delete user
                ctx.delete(USER)
                    .where(USER.ID.eq(id))
                    .execute()
            }
        }
    }

    private fun getFromDatabase(id: String): Optional<UserPermission> {
        val userPermission = UserPermission()
            .setId(id)

        if (id != UNRESTRICTED_USERNAME) {
            val result = withPool(poolName) {
                jooq.withRetry(sqlRetryProperties.reads) { ctx ->
                    ctx.select(USER.ADMIN)
                        .from(USER)
                        .where(USER.ID.eq(id))
                        .fetchOne()
                }
            }

            if (result == null) {
                log.debug("request for user {} not found in database", id)
                return Optional.empty()
            }

            userPermission.isAdmin = result.get(USER.ADMIN)
        }

        withPool(poolName) {
            jooq.withRetry(sqlRetryProperties.reads) { ctx ->
                resources.forEach { r ->
                    val userResources = ctx
                        .select(RESOURCE.BODY)
                        .from(RESOURCE)
                        .join(PERMISSION)
                        .on(
                            PERMISSION.RESOURCE_TYPE.eq(RESOURCE.RESOURCE_TYPE).and(
                                PERMISSION.RESOURCE_NAME.eq(RESOURCE.RESOURCE_NAME)
                            )
                        )
                        .join(USER)
                        .on(USER.ID.eq(PERMISSION.USER_ID))
                        .where(USER.ID.eq(id).and(
                            PERMISSION.RESOURCE_TYPE.eq(r.resourceType))
                        )
                        .fetch()
                        .map { record ->
                            objectMapper.readValue(record.get(RESOURCE.BODY), r.javaClass)
                        }
                    userPermission.addResources(userResources)
                }
            }
        }

        if (UNRESTRICTED_USERNAME != id) {
            userPermission.merge(getUnrestrictedUserPermission())
        }

        return Optional.of(userPermission)
    }

    private fun getUnrestrictedUserPermission(): UserPermission {
        var serverLastModified = withPool(poolName) {
            jooq.withRetry(sqlRetryProperties.reads) { ctx ->
                ctx.select(USER.UPDATED_AT)
                    .from(USER)
                    .where(USER.ID.eq(UNRESTRICTED_USERNAME))
                    .fetchOne(USER.UPDATED_AT)
            }
        }

        if (serverLastModified == null) {
            log.debug(
                "no last modified time available in database for user {} using default of {}",
                UNRESTRICTED_USERNAME,
                NO_UPDATED_AT
            )
            serverLastModified = NO_UPDATED_AT
        }

        return try {
            val userPermission = unrestrictedPermission[serverLastModified]
            if (userPermission != null && serverLastModified != NO_UPDATED_AT) {
                fallbackLastModified.set(serverLastModified)
            }
            userPermission!!
        } catch (ex: Throwable) {
            log.error(
                "failed reading user {} from cache for key {}", UNRESTRICTED_USERNAME, serverLastModified, ex
            )
            val fallback = fallbackLastModified.get()
            if (fallback != null) {
                val fallbackPermission = unrestrictedPermission.getIfPresent(fallback)
                if (fallbackPermission != null) {
                    log.warn(
                        "serving fallback permission for user {} from key {} as {}",
                        UNRESTRICTED_USERNAME,
                        fallback,
                        fallbackPermission
                    )
                    return fallbackPermission
                }
                log.warn("no fallback entry remaining in cache for key {}", fallback)
            }
            if (ex is RuntimeException) {
                throw ex
            }
            throw IntegrationException(ex)
        }
    }

    private fun reloadUnrestricted(cacheKey: Long): UserPermission {
        return getFromDatabase(UNRESTRICTED_USERNAME)
            .map { p ->
                log.debug("reloaded user {} for key {} as {}", UNRESTRICTED_USERNAME, cacheKey, p)
                p
            }
            .orElseThrow {
                log.error(
                    "loading user {} for key {} failed, no permissions returned",
                    UNRESTRICTED_USERNAME,
                    cacheKey
                )
                PermissionRepositoryException("Failed to read unrestricted user")
            }
    }
}
