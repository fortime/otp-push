@file:Suppress("PropertyName")

package fyi.fortime.otppushmobile.data

import kotlinx.serialization.Serializable

@Serializable
data class AuthConfig(val google_client_id: String)

@Serializable
data class GoogleAuthRequest(
    val id_token: String,
    val device_id: String? = null,
    val fcm_token: String? = null,
    val create_device: Boolean = false
)

@Serializable
data class AuthResponse(
    val access_token: String,
    val user: UserDto
)

@Serializable
data class SubmitOtpRequest(
    val request_id: String,
    val otp_code: String
)

@Serializable
data class UpdateFcmTokenRequest(
    val device_id: String,
    val fcm_token: String
)

@Serializable
data class LogoutRequest(
    val device_id: String
)

@Serializable
data class UserDto(
    val id: String,
    val email: String,
    val enabled: Boolean,
    val admin: Boolean,
    val token_version_at: String
)

@Serializable
data class UserLimitsDto(
    val max_otp_records: Int,
    val max_tokens_per_record: Int
)

@Serializable
data class UpdateLimitsRequest(
    val max_otp_records: Int,
    val max_tokens_per_record: Int
)

@Serializable
data class OtpRecordDto(
    val id: String,
    val name: String,
    val service_identifier: String,
    val created_at: String
)

@Serializable
data class CreateOtpRecordRequest(
    val name: String,
    val service_identifier: String
)

@Serializable
data class OtpRequestDto(
    val id: String,
    val otp_record_id: String,
    val otp_record_name: String,
    val service_identifier: String,
    val status: String,
    val pub_key: String? = null,
    val created_at: String
)

@Serializable
data class PaginatedResponse<T>(
    val items: List<T>,
    val total: Long
)

@Serializable
data class ApiAccessTokenDto(
    val id: String,
    val name: String,
    val masked_token: String,
    val last_used_at: String?,
    val created_at: String
)

@Serializable
data class CreateTokenRequest(
    val name: String
)

@Serializable
data class CreateTokenResponse(
    val id: String,
    val name: String,
    val token: String
)

@Serializable
data class CachedOtpRecords(
    val records: List<OtpRecordDto>,
    val page: Long,
    val hasMore: Boolean,
)
