package com.example.portainerapp.network

import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path

// Data models

data class AuthRequest(val Username: String, val Password: String)

data class AuthResponse(val jwt: String)

data class DockerNode(
    val ID: String,
    val Description: Description?
)

data class Description(
    val Hostname: String?
)

// Retrofit service definition
interface PortainerService {
    @POST("api/auth")
    suspend fun authenticate(@Body credentials: AuthRequest): AuthResponse

    @GET("api/endpoints/{endpointId}/docker/nodes")
    suspend fun listNodes(
        @Path("endpointId") endpointId: Int,
        @Header("Authorization") bearer: String
    ): List<DockerNode>
}

object PortainerApi {
    fun create(baseUrl: String): PortainerService = Retrofit.Builder()
        .baseUrl(baseUrl)
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(PortainerService::class.java)
}
