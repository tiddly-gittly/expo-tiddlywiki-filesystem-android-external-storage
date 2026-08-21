package expo.modules.externalstorage

import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock

/**
 * Serializes JGit operations that target the same repository.
 *
 * Expo async functions may run concurrently. Even status reads can refresh
 * JGit's DirCache while a commit or reset is replacing the index, so every
 * native Git entry point shares one lock per canonical work-tree path.
 *
 * Locks intentionally remain in this small process-local map. Removing an
 * idle lock can race with a new waiter and create two locks for one repository.
 */
internal object GitRepositoryLock {
  private val locks = ConcurrentHashMap<String, ReentrantLock>()

  fun <T> withLock(gitRootDir: String, operation: () -> T): T {
    val repositoryKey = try {
      File(gitRootDir).canonicalFile.absolutePath
    } catch (_: IOException) {
      File(gitRootDir).absoluteFile.absolutePath
    }
    val lock = locks.computeIfAbsent(repositoryKey) { ReentrantLock(true) }
    lock.lock()
    return try {
      operation()
    } finally {
      lock.unlock()
    }
  }
}
