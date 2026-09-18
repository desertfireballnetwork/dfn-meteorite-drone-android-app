package au.edu.fireballs.stage4.data.tiles

import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes

@Suppress("SwallowedException")
internal fun replaceFileAtomically(
    source: File,
    target: File,
    move: (source: File, target: File, atomic: Boolean) -> Unit = ::moveFile,
) {
    try {
        move(source, target, true)
    } catch (error: AtomicMoveNotSupportedException) {
        move(source, target, false)
    }
}

private fun moveFile(
    source: File,
    target: File,
    atomic: Boolean,
) {
    if (atomic) {
        Files.move(
            source.toPath(),
            target.toPath(),
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE,
        )
    } else {
        Files.move(
            source.toPath(),
            target.toPath(),
            StandardCopyOption.REPLACE_EXISTING,
        )
    }
}

internal fun deleteRegularFile(file: File) {
    val path = file.toPath()
    val isSymbolicLink = Files.isSymbolicLink(path)
    val exists = file.exists()
    if (isSymbolicLink || (exists && !Files.isRegularFile(path))) {
        throw IOException("Refusing to delete a non-regular file")
    }
    if (exists && !file.delete()) {
        throw IOException("Failed to delete file")
    }
}

internal fun pruneEmptyDirectories(
    start: File,
    root: File,
) {
    var current: File? = start
    while (current != null && current.path != root.path) {
        if (Files.isSymbolicLink(current.toPath())) {
            return
        }
        val children = current.listFiles()
        if (children == null || children.isNotEmpty()) {
            return
        }
        if (!current.delete()) {
            return
        }
        current = current.parentFile
    }
}

internal fun deleteTreeNoFollow(root: File) {
    val rootPath = root.toPath()
    if (!Files.exists(rootPath, LinkOption.NOFOLLOW_LINKS)) {
        return
    }
    if (!Files.isDirectory(rootPath, LinkOption.NOFOLLOW_LINKS)) {
        throw IOException("Refusing to clear a non-directory root")
    }
    Files.walkFileTree(
        rootPath,
        object : SimpleFileVisitor<Path>() {
            override fun visitFile(
                file: Path,
                attrs: BasicFileAttributes,
            ): FileVisitResult {
                if (attrs.isRegularFile) {
                    runCatching { Files.deleteIfExists(file) }
                }
                return FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(
                file: Path,
                exc: IOException,
            ): FileVisitResult = FileVisitResult.CONTINUE

            override fun postVisitDirectory(
                dir: Path,
                exc: IOException?,
            ): FileVisitResult {
                if (dir.toFile().listFiles()?.isEmpty() == true) {
                    runCatching { Files.deleteIfExists(dir) }
                }
                return FileVisitResult.CONTINUE
            }
        },
    )
}
