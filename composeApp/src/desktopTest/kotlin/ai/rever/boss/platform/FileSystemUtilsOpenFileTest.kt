package ai.rever.boss.platform

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * [FileSystemUtils.openCommandFor] builds the argv `openFile` hands to `Runtime.exec`.
 *
 * The Windows branch used to be `cmd /c start "" <path>`. `cmd` re-parses the line it is
 * given, and the JDK only quotes an argv element that contains a space or a tab - so a path
 * with no space but with a shell metacharacter (`&`, `^`, `|`, `<`, `>`) reached `cmd` as
 * command syntax rather than as part of a filename. `C:\Users\dev\Downloads\R&D.pdf` split
 * into `start "" C:\Users\dev\Downloads\R` (opens nothing useful) and `D.pdf` (run as the
 * next command). Routing through `explorer.exe <path>` removes the shell from the picture
 * entirely: the array form of `exec` passes each element straight to process creation, so
 * `explorer.exe` only ever sees the path as one argument, metacharacters included.
 */
class FileSystemUtilsOpenFileTest {
    @Test
    fun `windows command carries the path unmodified with no shell to re-parse it`() {
        val file = File("C:\\Users\\dev\\Downloads\\R&D.pdf")

        val command = FileSystemUtils.openCommandFor("windows 11", file)

        assertEquals(arrayOf("explorer.exe", file.absolutePath).toList(), command?.toList())
    }

    @Test
    fun `windows command never shells through cmd`() {
        val file = File("C:\\Users\\dev\\Downloads\\report.pdf")

        val command = FileSystemUtils.openCommandFor("windows", file)

        requireNotNull(command)
        assertEquals(false, command.any { it.equals("cmd", ignoreCase = true) })
    }

    @Test
    fun `mac command opens the path directly`() {
        val file = File("/Users/dev/Downloads/report.pdf")

        val command = FileSystemUtils.openCommandFor("mac os x", file)

        assertEquals(arrayOf("open", file.absolutePath).toList(), command?.toList())
    }

    @Test
    fun `linux command opens the path directly`() {
        val file = File("/home/dev/Downloads/report.pdf")

        val command = FileSystemUtils.openCommandFor("linux", file)

        assertEquals(arrayOf("xdg-open", file.absolutePath).toList(), command?.toList())
    }

    @Test
    fun `unknown os returns no command`() {
        val file = File("/tmp/report.pdf")

        val command = FileSystemUtils.openCommandFor("some other os", file)

        assertNull(command)
    }
}
