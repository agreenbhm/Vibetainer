package com.example.portainerapp.network

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
import okhttp3.ResponseBody
import retrofit2.http.Streaming

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

    @GET("api/endpoints/{endpointId}/docker/containers/json")
    suspend fun listContainers(
        @Path("endpointId") endpointId: Int,
        @Query("all") all: Boolean = false,
        @Header("X-PortainerAgent-Target") agentTarget: String? = null
    ): List<ContainerSummary>

    @GET("api/endpoints/{endpointId}/docker/images/json")
    suspend fun listImages(
        @Path("endpointId") endpointId: Int,
        @Header("X-PortainerAgent-Target") agentTarget: String? = null
    ): List<ImageSummary>

    @GET("api/endpoints/{endpointId}/docker/volumes")
    suspend fun listVolumes(
        @Path("endpointId") endpointId: Int,
        @Header("X-PortainerAgent-Target") agentTarget: String? = null
    ): VolumesResponse

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
    val Name: String?
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

data class Stack(
    val Id: Int?,
    val Name: String?,
    val EndpointId: Int?
)

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
        val prefs = com.example.portainerapp.util.Prefs(context)
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
            .addInterceptor(authInterceptor)
            .addInterceptor(logging)

        // Configure HTTP proxy if enabled
        if (prefs.proxyEnabled()) {
            val host = prefs.proxyHost()
            val port = prefs.proxyPort()
            if (host.isNotBlank() && port in 1..65535) {
                val proxy = java.net.Proxy(java.net.Proxy.Type.HTTP, java.net.InetSocketAddress(host, port))
                builder.proxy(proxy)
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
