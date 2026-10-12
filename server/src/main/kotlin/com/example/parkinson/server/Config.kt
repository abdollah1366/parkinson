package com.example.parkinson.server

/** Settings read from environment variables (see server/.env.example). */
data class Config(
    val port: Int,
    val databaseUrl: String,
    val databaseUser: String,
    val databasePassword: String,
    /** Signs staff login tokens. At least 32 characters. */
    val jwtSecret: String,
    /** Key for the national-ID hash. At least 32 characters; changing it makes known patients look new. */
    val nationalIdPepper: String,
    /** Creates this admin account on start if no account has the mobile yet. */
    val adminMobile: String?,
    val adminPassword: String?,
    val adminName: String,
    /** Behind a reverse proxy (Caddy): take the client address from X-Forwarded-For. */
    val trustProxy: Boolean,
    /** Create the database on start if it is missing (test computers; Docker creates it by itself). */
    val createDatabase: Boolean = false,
    /** Folder of a PostgreSQL the server starts itself (local testing); null to use [databaseUrl]. */
    val embeddedDataDir: String? = null,
) {
    init {
        require(jwtSecret.length >= 32) { "JWT_SECRET must be at least 32 characters" }
        require(nationalIdPepper.length >= 32) { "NATIONAL_ID_PEPPER must be at least 32 characters" }
    }

    override fun toString(): String = "Config(port=$port, databaseUrl=$databaseUrl, trustProxy=$trustProxy)"

    companion object {
        fun fromEnv(env: Map<String, String> = System.getenv()): Config {
            fun required(name: String) = env[name]?.takeIf { it.isNotBlank() } ?: error("$name is not set")
            val embedded = env["EMBEDDED_DB_DIR"]?.takeIf { it.isNotBlank() }
            return Config(
                port = env["PORT"]?.toIntOrNull() ?: 8080,
                databaseUrl = if (embedded != null) "" else required("DATABASE_URL"),
                databaseUser = if (embedded != null) "" else required("DATABASE_USER"),
                databasePassword = if (embedded != null) "" else required("DATABASE_PASSWORD"),
                jwtSecret = required("JWT_SECRET"),
                nationalIdPepper = required("NATIONAL_ID_PEPPER"),
                adminMobile = env["ADMIN_MOBILE"]?.takeIf { it.isNotBlank() },
                adminPassword = env["ADMIN_PASSWORD"]?.takeIf { it.isNotBlank() },
                adminName = env["ADMIN_NAME"]?.takeIf { it.isNotBlank() } ?: "مدیر سامانه",
                trustProxy = env["TRUST_PROXY"] == "true",
                createDatabase = env["CREATE_DATABASE"] == "true",
                embeddedDataDir = embedded,
            )
        }
    }
}
