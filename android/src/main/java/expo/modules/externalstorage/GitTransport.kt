package expo.modules.externalstorage

import android.util.Base64
import org.eclipse.jgit.api.TransportCommand
import org.eclipse.jgit.transport.TransportHttp
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import org.json.JSONObject

internal object GitTransport {

  /** Apply custom HTTP headers and credentials to a JGit transport command. */
  fun applyHeaders(
    command: TransportCommand<*, *>,
    headers: String?
  ) {
    // Parse custom headers JSON
    val headerMap = mutableMapOf<String, String>()
    if (headers != null) {
      try {
        val headerObj = JSONObject(headers)
        for (key in headerObj.keys()) {
          headerMap[key] = headerObj.getString(key)
        }
      } catch (e: Exception) {
        android.util.Log.w("GitHelper", "Failed to parse headers: ${e.message}")
      }
    }

    // Extract Basic Auth from Authorization header and set as CredentialsProvider.
    val authHeader = headerMap["Authorization"] ?: headerMap["authorization"]
    if (authHeader != null && authHeader.startsWith("Basic ", ignoreCase = true)) {
      try {
        val decoded = String(Base64.decode(authHeader.substring(6), Base64.DEFAULT))
        val colonIndex = decoded.indexOf(':')
        val username = if (colonIndex >= 0) decoded.substring(0, colonIndex) else ""
        val password = if (colonIndex >= 0) decoded.substring(colonIndex + 1) else decoded
        command.setCredentialsProvider(UsernamePasswordCredentialsProvider(username, password))
      } catch (e: Exception) {
        android.util.Log.w("GitHelper", "Failed to decode Basic auth: ${e.message}")
      }
    }

    command.setTransportConfigCallback { transport ->
      if (transport is TransportHttp) {
        transport.setAdditionalHeaders(headerMap)
      }
    }
  }
}
