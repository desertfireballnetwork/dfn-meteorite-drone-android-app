package au.edu.fireballs.stage4.data.repository

import java.io.File
import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

internal suspend fun regularFileBytes(root: File): Long {
    val path = root.toPath()
    if (!Files.exists(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
        return 0L
    }
    var total = 0L
    Files.walkFileTree(
        path,
        object : SimpleFileVisitor<Path>() {
            override fun visitFile(
                file: Path,
                attrs: BasicFileAttributes,
            ): FileVisitResult {
                if (attrs.isRegularFile) {
                    total = Math.addExact(total, attrs.size())
                }
                return FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(
                file: Path,
                error: IOException,
            ): FileVisitResult =
                if (error is NoSuchFileException || error is AccessDeniedException) {
                    FileVisitResult.CONTINUE
                } else {
                    throw error
                }
        },
    )
    return total
}
