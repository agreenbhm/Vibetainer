package com.agreenbhm.vibetainer.network

import android.content.Context
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.POST
import retrofit2.http.Header
import retrofit2.http.Body
import okhttp3.ResponseBody
import retrofit2.http.Streaming
import retrofit2.http.PUT
import com.google.gson.annotations.SerializedName
import java.util.concurrent.TimeUnit

// Data models (minimal for nodes)
data class DockerNode(
    val ID: String,
    val Description: Description?
)

data class Description(
    val Hostname: String?,
    val Resources: Resources?
)

data class Resources(
    val NanoCPUs: Long?,
    val MemoryBytes: Long?
)

data class Endpoint(
    val Id: Int,
    val Name: String
)

data class SystemDf(
    val LayersSize: Long?,
    val Containers: List<DfContainer>?,
    val Images: List<DfImage>?,
    val Volumes: List<DfVolume>?
)

data class DfContainer(val SizeRootFs: Long?)
data class DfImage(val Size: Long?)
data class DfVolume(val UsageData: DfUsageData?)
data class DfUsageData(val Size: Long?)

data class DockerInfo(
    val NCPU: Int?,
    val MemTotal: Long?
)

// Retrofit service definition
interface PortainerService {
    @GET("api/endpoints")
    suspend fun listEndpoints(): List<Endpoint>
    @GET("api/endpoints/{endpointId}/docker/nodes")
    suspend fun listNodes(
        @Path("endpointId") endpointId: Int,
    ): List<DockerNode>

    @GET("api/endpoints/{endpointId}/docker/nodes/{nodeId}")
    suspend fun getNode(
        @Path("endpointId") endpointId: Int,
        @Path("nodeId") nodeId: String
    ): DockerNode

    @GET("api/endpoints/{endpointId}/docker/system/df")
    suspend fun systemDf(
        @Path("endpointId") endpointId: Int
    ): SystemDf

    @GET("api/endpoints/{endpointId}/docker/info")
    suspend fun info(
        @Path("endpointId") endpointId: Int
    ): DockerInfo

    @GET("api/endpoints/{endpointId}/docker/services")
    suspend fun listServices(
        @Path("endpointId") endpointId: Int
    ): List<Service>

    @GET("api/stacks")
    suspend fun listStacks(): List<Stack>

    @GET("api/stacks/{id}/file")
    suspend fun getStackFile(
        @Path("id") id: Int
    ): StackFileResponse

    @PUT("api/stacks/{id}")
    suspend fun updateStack(
        @Path("id") id: Int,
        @Query("endpointId") endpointId: Int,
        @Body body: StackUpdateRequest
    ): retrofit2.Response<Unit>

    // Create a new stack from raw compose content
    @POST("api/stacks/create/swarm/string")
    suspend fun createStackSwarmFromString(
        @Query("endpointId") endpointId: Int,
        @Body body: StackCreateRequest
    ): retrofit2.Response<Unit>

    @POST("api/stacks/create/standalone/string")
    suspend fun createStackStandaloneFromString(
        @Query("endpointId") endpointId: Int,
        @Body body: StackCreateRequest
    ): retrofit2.Response<Unit>

    @GET("api/endpoints/{endpointId}/docker/swarm")
    suspend fun swarmInfo(
        @Path("endpointId") endpointId: Int
    ): SwarmInfo

    // Stack lifecycle
    @POST("api/stacks/{id}/start")
    suspend fun stackStart(
        @Path("id") id: Int,
        @Query("endpointId") endpointId: Int
    ): retrofit2.Response<Unit>

    @POST("api/stacks/{id}/stop")
    suspend fun stackStop(
        @Path("id") id: Int,
        @Query("endpointId") endpointId: Int
    ): retrofit2.Response<Unit>

    @GET("api/endpoints/{endpointId}/docker/containers/json")
    suspend fun listContainers(
        @Path("endpointId") endpointId: Int,
        @Query("all") all: Boolean = true,
        @Header("X-PortainerAgent-Target") agentTarget: String? = null
    ): List<ContainerSummary>

    @GET("api/endpoints/{endpointId}/docker/images/json")
    suspend fun listImages(
        @Path("endpointId") endpointId: Int,
        @Header("X-PortainerAgent-Target") agentTarget: String? = null
    ): List<ImageSummary>

    // Prune unused images (Docker API: POST /images/prune)
    @POST("api/endpoints/{endpointId}/docker/images/prune")
    suspend fun pruneImages(
        @Path("endpointId") endpointId: Int,
        @Body body: ImagePruneRequest? = null,
        @Header("X-PortainerAgent-Target") agentTarget: String? = null
    ): ImagePruneResponse

    // Portainer environment-level images with usage info
    @GET("api/docker/{environmentId}/images")
    suspend fun listEnvironmentImages(
        @Path("environmentId") environmentId: Int,
        @Query("withUsage") withUsage: Boolean = false
    ): List<EnvironmentImage>

    @retrofit2.http.DELETE("api/endpoints/{endpointId}/docker/images/{id}")
    suspend fun deleteImage(
        @Path("endpointId") endpointId: Int,
        @Path("id") id: String,
        @Query("force") force: Int = 1,
        @Header("X-PortainerAgent-Target") agentTarget: String? = null
    ): retrofit2.Response<Unit>

    @GET("api/endpoints/{endpointId}/docker/volumes")
    suspend fun listVolumes(
        @Path("endpointId") endpointId: Int,
        @Header("X-PortainerAgent-Target") agentTarget: String? = null
    ): VolumesResponse

    // Filtered volumes using Docker API filters (e.g. filters={"dangling":["true"]})
    @GET("api/endpoints/{endpointId}/docker/volumes")
    suspend fun listVolumesFiltered(
        @Path("endpointId") endpointId: Int,
        @Query("filters") filters: String,
        @Header("X-PortainerAgent-Target") agentTarget: String? = null
    ): VolumesResponse

    @retrofit2.http.DELETE("api/endpoints/{endpointId}/docker/volumes/{name}")
    suspend fun deleteVolume(
        @Path("endpointId") endpointId: Int,
        @Path("name") name: String,
        @Query("force") force: Int = 1,
        @Header("X-PortainerAgent-Target") agentTarget: String? = null
    ): retrofit2.Response<Unit>

    // Prune unused volumes (Docker API: POST /volumes/prune)
    @POST("api/endpoints/{endpointId}/docker/volumes/prune")
    suspend fun pruneVolumes(
        @Path("endpointId") endpointId: Int,
        @Body body: VolumePruneRequest? = null,
        @Header("X-PortainerAgent-Target") agentTarget: String? = null
    ): VolumePruneResponse

    @GET("api/endpoints/{endpointId}/docker/containers/{id}/stats")
    suspend fun containerStats(
        @Path("endpointId") endpointId: Int,
        @Path("id") id: String,
        @Query("stream") stream: Boolean = false,
        @Header("X-PortainerAgent-Target") agentTarget: String? = null
    ): ContainerStats

    @GET("api/endpoints/{endpointId}/docker/containers/{id}/json")
    suspend fun containerInspect(
        @Path("endpointId") endpointId: Int,
        @Path("id") id: String,
        @Header("X-PortainerAgent-Target") agentTarget: String? = null
    ): ContainerInspect

    @Streaming
    @GET("api/endpoints/{endpointId}/docker/containers/{id}/logs")
    suspend fun containerLogs(
        @Path("endpointId") endpointId: Int,
        @Path("id") id: String,
        @Query("stdout") stdout: Int = 1,
        @Query("stderr") stderr: Int = 1,
        @Query("tail") tail: Int = 200,
        @Query("timestamps") timestamps: Int = 1,
        @Query("follow") follow: Int = 0,
        @Header("X-PortainerAgent-Target") agentTarget: String? = null
    ): ResponseBody

    @POST("api/endpoints/{endpointId}/docker/containers/{id}/start")
    suspend fun containerStart(
        @Path("endpointId") endpointId: Int,
        @Path("id") id: String,
        @Header("X-PortainerAgent-Target") agentTarget: String? = null
    )

    @POST("api/endpoints/{endpointId}/docker/containers/{id}/stop")
    suspend fun containerStop(
        @Path("endpointId") endpointId: Int,
        @Path("id") id: String,
        @Header("X-PortainerAgent-Target") agentTarget: String? = null
    )

    @retrofit2.http.DELETE("api/endpoints/{endpointId}/docker/containers/{id}")
    suspend fun containerRemove(
        @Path("endpointId") endpointId: Int,
        @Path("id") id: String,
        @Query("force") force: Int = 0,
        @Query("v") v: Int = 0,
        @Header("X-PortainerAgent-Target") agentTarget: String? = null
    ): retrofit2.Response<Unit>

    // Services inspect/update
    @GET("api/endpoints/{endpointId}/docker/services/{id}")
    suspend fun serviceInspect(
        @Path("endpointId") endpointId: Int,
        @Path("id") id: String
    ): ServiceInspect

    @POST("api/endpoints/{endpointId}/docker/services/{id}/update")
    suspend fun serviceUpdate(
        @Path("endpointId") endpointId: Int,
        @Path("id") id: String,
        @Query("version") version: Long,
        @Body spec: ServiceSpecFull
    ): retrofit2.Response<Unit>
}

data class ContainerSummary(
    val Id: String,
    val Names: List<String>?,
    val State: String?,
    val Labels: Map<String, String>?,
    val Image: String?
)

data class ContainerStats(
    val cpu_stats: CpuStats?,
    val precpu_stats: CpuStats?,
    val memory_stats: MemoryStats?
)

data class CpuStats(
    val cpu_usage: CpuUsage?,
    val system_cpu_usage: Long?,
    val online_cpus: Int?
)

data class CpuUsage(
    val total_usage: Long?,
    val percpu_usage: List<Long>?
)

data class MemoryStats(
    val usage: Long?,
    val limit: Long?
)


data class ImageSummary(
    val Id: String?,
    val RepoTags: List<String>?
)


data class VolumesResponse(
    val Volumes: List<Volume>?
)


data class Volume(
    @SerializedName("Name") val Name: String?,
    @SerializedName("Portainer") val portainer: Map<String, Any>?
){
    val nodeName: String?
        get() = (portainer?.get("Agent") as? Map<*, *>)?.get("NodeName") as? String
    val isUnused: Boolean
        get() {
            // Try common Portainer/Docker usage fields
            val usage = portainer?.get("UsageData") as? Map<*, *>
            val refCount = usage?.get("RefCount") as? Number
            if (refCount != null) return refCount.toLong() == 0L
            val usage2 = portainer?.get("Usage") as? Map<*, *>
            val refs = usage2?.get("RefCount") as? Number
            if (refs != null) return refs.toLong() == 0L
            // Fallback: check top-level portainer flag
            val unusedFlag = portainer?.get("Unused") as? Boolean
            if (unusedFlag != null) return unusedFlag
            return false
        }
}

// Prune request/response models
data class VolumePruneRequest(
    @SerializedName("filters") val filters: Map<String, List<String>>? = null
)

data class VolumePruneResponse(
    @SerializedName("VolumesDeleted") val VolumesDeleted: List<String>?,
    @SerializedName("SpaceReclaimed") val SpaceReclaimed: Long?
)

// Image prune models
data class ImagePruneRequest(
    @SerializedName("filters") val filters: Map<String, List<String>>? = null
)

data class ImagePruneResponse(
    @SerializedName("ImagesDeleted")
    val ImagesDeleted: List<ImageDeletedEntry>?,   // <-- List of objects
    @SerializedName("SpaceReclaimed")
    val SpaceReclaimed: Long?
)

data class ImageDeletedEntry(
    @SerializedName("Untagged") val Untagged: String? = null,
    @SerializedName("Deleted") val Deleted: String? = null
)

// Environment-level image entry returned by Portainer's /api/docker/{env}/images
data class EnvironmentImage(
    @SerializedName("created") val created: Long?,
    @SerializedName("nodeName") val nodeName: String?,
    @SerializedName("id") val id: String?,
    @SerializedName("size") val size: Long?,
    @SerializedName("tags") val tags: List<String>?,
    @SerializedName("used") val used: Boolean?
)


data class ContainerInspect(
    val Name: String?,
    val Image: String?,
    val State: ContainerState?
)

data class ContainerState(
    val Status: String?,
    val Running: Boolean?
)

data class Service(
    val ID: String?,
    val Spec: ServiceSpec?
)
data class ServiceSpec(
    val Name: String?
)

// Service inspect/update models (minimal)
data class ServiceInspect(
    val ID: String?,
    val Version: Version?,
    val Spec: ServiceSpecFull?
)
data class Version(val Index: Long?)
data class ServiceSpecFull(
    val Name: String?,
    val TaskTemplate: TaskTemplate?,
    val Labels: Map<String, String>? = null
)
data class TaskTemplate(
    val ForceUpdate: Int?,
    val ContainerSpec: ContainerSpec?
)
data class ContainerSpec(val Image: String?)

data class Stack(
    val Id: Int?,
    val Name: String?,
    val EndpointId: Int?
)

data class StackFileResponse(
    val StackFileContent: String?
)

data class StackUpdateRequest(
    @SerializedName("StackFileContent") val StackFileContent: String,
    @SerializedName("Prune") val Prune: Boolean = false
)

data class StackCreateRequest(
    @SerializedName("env") val env: List<StackEnvVar> = emptyList(),
    @SerializedName("fromAppTemplate") val fromAppTemplate: Boolean = false,
    @SerializedName("name") val name: String,
    @SerializedName("registries") val registries: List<Int> = emptyList(),
    @SerializedName("stackFileContent") val stackFileContent: String,
    @SerializedName("swarmID") val swarmID: String? = null
)

data class StackEnvVar(
    @SerializedName("name") val name: String,
    @SerializedName("value") val value: String
)

data class SwarmInfo(@SerializedName("ID") val ID: String?)

object PortainerApi {
    fun create(baseUrl: String, apiToken: String, enableLogging: Boolean = true): PortainerService {
        val authInterceptor = Interceptor { chain ->
            val newReq = chain.request().newBuilder()
                .addHeader("X-API-Key", apiToken)
                .build()
            chain.proceed(newReq)
        }

        val logging = HttpLoggingInterceptor().apply {
            level = if (enableLogging) HttpLoggingInterceptor.Level.BASIC else HttpLoggingInterceptor.Level.NONE
        }

        val client = OkHttpClient.Builder()
            .connectTimeout(60, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .addInterceptor(authInterceptor)
            .addInterceptor(logging)
            .build()

        return Retrofit.Builder()
            .baseUrl(ensureTrailingSlash(baseUrl))
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(PortainerService::class.java)
    }

    private fun ensureTrailingSlash(url: String): String = if (url.endsWith("/")) url else "$url/"

    fun create(context: Context, baseUrl: String, apiToken: String, enableLogging: Boolean = true): PortainerService {
        val prefs = com.agreenbhm.vibetainer.util.Prefs(context)
        val authInterceptor = Interceptor { chain ->
            val newReq = chain.request().newBuilder()
                .addHeader("X-API-Key", apiToken)
                .build()
            chain.proceed(newReq)
        }

        val logging = HttpLoggingInterceptor().apply {
            level = if (enableLogging) HttpLoggingInterceptor.Level.BASIC else HttpLoggingInterceptor.Level.NONE
        }

        val builder = OkHttpClient.Builder()
            .connectTimeout(60, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .addInterceptor(authInterceptor)
            .addInterceptor(logging)

        // Configure HTTP proxy if enabled
        if (prefs.proxyEnabled()) {
            val url = prefs.proxyUrl()
            if (url.isNotBlank()) {
                try {
                    val normalized = prefs.normalizeBaseUrl(url).removeSuffix("/")
                    val scheme = if (normalized.startsWith("https://", true)) "https" else "http"
                    val after = normalized.substringAfter("://")
                    val host = after.substringBefore(":")
                    val portStr = after.substringAfter(":", "")
                    val port = portStr.toIntOrNull() ?: if (scheme == "https") 443 else 80
                    val proxy = java.net.Proxy(java.net.Proxy.Type.HTTP, java.net.InetSocketAddress(host, port))
                    builder.proxy(proxy)
                } catch (_: Exception) { /* ignore invalid proxy URL */ }
            } else {
                // Back-compat: host/port settings
                val host = prefs.proxyHost()
                val port = prefs.proxyPort()
                if (host.isNotBlank() && port in 1..65535) {
                    val proxy = java.net.Proxy(java.net.Proxy.Type.HTTP, java.net.InetSocketAddress(host, port))
                    builder.proxy(proxy)
                }
            }
        }

        // Optionally ignore SSL errors (trust all + accept all hostnames)
        if (prefs.ignoreSslErrors()) {
            try {
                val trustAll = arrayOf<javax.net.ssl.TrustManager>(object : javax.net.ssl.X509TrustManager {
                    override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = arrayOf()
                    override fun checkClientTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {}
                    override fun checkServerTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {}
                })
                val sslContext = javax.net.ssl.SSLContext.getInstance("TLS")
                sslContext.init(null, trustAll, java.security.SecureRandom())
                val sslSocketFactory = sslContext.socketFactory
                builder.sslSocketFactory(sslSocketFactory, trustAll[0] as javax.net.ssl.X509TrustManager)
                builder.hostnameVerifier { _, _ -> true }
            } catch (_: Exception) {
                // If SSL context fails, proceed without relaxing SSL
            }
        }

        val client = builder.build()
        return Retrofit.Builder()
            .baseUrl(ensureTrailingSlash(baseUrl))
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(PortainerService::class.java)
    }
}
