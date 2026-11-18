package org.projectcontinuum.feature.ai.node

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

class UnslothTrainerCommandTest {

    private val hostile = listOf("x;id", "\$(id)", "`id`", "a b", "\"", "'; rm -rf / #", "a\nb", "\$HOME")

    private val workerEnv = mapOf(
        "PATH" to "/usr/bin:/bin",
        "HOME" to "/home/continuum",
        "LC_ALL" to "C.UTF-8",
        "HF_HOME" to "/cache/huggingface",
        "CUDA_VISIBLE_DEVICES" to "0",
        "LD_LIBRARY_PATH" to "/usr/local/cuda/lib64",
        "HTTPS_PROXY" to "http://proxy:3128",
        "CONTINUUM_MINIO_PASSWORD" to "minio-secret",
        "AWS_SECRET_ACCESS_KEY" to "aws",
        "SPRING_DATASOURCE_PASSWORD" to "db",
        "MY_ARBITRARY_SECRET" to "s3cr3t",
        "PYTHONPATH" to "/elsewhere",
        "BASH_ENV" to "/tmp/evil.sh"
    )

    private fun config(model: String = "unsloth/Phi-4", systemPrompt: String? = null, inputColumn: String = "instruction") =
        TrainingConfig.from(
            mapOf<String, Any>("model" to model, "inputColumn" to inputColumn) +
                (systemPrompt?.let { mapOf("systemPrompt" to it) } ?: emptyMap()),
            10_000
        )

    private fun build(args: List<String>, extra: Map<String, String> = emptyMap(), windows: Boolean = false) =
        UnslothTrainerCommand.build("/opt/venv", "/tmp/train.py", args, workerEnv, extra, windows)

    @Test
    fun `runs the venv interpreter directly without a shell`() {
        val cmd = build(listOf("--ipc"))
        assertEquals(listOf("/opt/venv/bin/python", "/tmp/train.py", "--ipc"), cmd.args)
        assertFalse(cmd.args.any { it == "bash" || it == "sh" || it == "-c" || it == "cmd" || it == "/c" })
    }

    @Test
    fun `windows uses Scripts python exe without cmd`() {
        val cmd = UnslothTrainerCommand.build("C:\\venv", "C:\\t\\train.py", listOf("--ipc"), mapOf("Path" to "C:\\Windows"), emptyMap(), true)
        assertEquals(listOf("C:\\venv\\Scripts\\python.exe", "C:\\t\\train.py", "--ipc"), cmd.args)
        assertEquals("C:\\venv\\Scripts;C:\\Windows", cmd.env["Path"])
    }

    @Test
    fun `hostile property values are single literal argv elements`() {
        for (value in hostile) {
            val args = UnslothTrainerCommand.scriptArguments(
                config(model = value, systemPrompt = value, inputColumn = value), "/data/in.parquet", "/out"
            )
            val cmd = build(args)
            for (flag in listOf("--model", "--system-prompt", "--input-column")) {
                val i = cmd.args.indexOf(flag)
                assertTrue(i >= 0, "$flag missing")
                assertEquals(value, cmd.args[i + 1], "$flag value for <$value>")
            }
            assertEquals(3, cmd.args.count { it == value }, "value <$value> must not be split or merged")
        }
    }

    @Test
    fun `all arguments are reproduced`() {
        val props = mapOf<String, Any>(
            "model" to "m", "inputColumn" to "q", "outputColumn" to "a", "systemPrompt" to "be nice",
            "epochs" to 3, "batchSize" to 4, "gradientAccumulation" to 8, "learningRate" to 0.001,
            "maxSeqLength" to 1024, "loraR" to 8, "loraAlpha" to 32, "loraDropout" to 0.1, "warmupSteps" to 7,
            "weightDecay" to 0.02, "seed" to 1, "saveSteps" to 50, "loggingSteps" to 5, "use4Bit" to false,
            "parquetBatchSize" to 500
        )
        val args = UnslothTrainerCommand.scriptArguments(TrainingConfig.from(props, 10_000), "/d.parquet", "/o")
        assertEquals(
            listOf(
                "--data", "/d.parquet", "--model", "m", "--output", "/o", "--input-column", "q", "--output-column", "a",
                "--epochs", "3", "--batch-size", "4", "--gradient-accumulation", "8", "--learning-rate", "0.001",
                "--max-seq-length", "1024", "--lora-r", "8", "--lora-alpha", "32", "--lora-dropout", "0.1",
                "--warmup-steps", "7", "--weight-decay", "0.02", "--seed", "1", "--save-steps", "50",
                "--logging-steps", "5", "--parquet-batch-size", "500", "--silent", "--ipc",
                "--system-prompt", "be nice", "--no-4bit"
            ),
            args
        )
    }

    @Test
    fun `env allow-list drops worker secrets and keeps trainer vars`() {
        val env = build(emptyList()).env
        assertFalse(env.containsKey("MY_ARBITRARY_SECRET"))
        assertFalse(env.containsKey("CONTINUUM_MINIO_PASSWORD"))
        assertFalse(env.containsKey("AWS_SECRET_ACCESS_KEY"))
        assertFalse(env.containsKey("SPRING_DATASOURCE_PASSWORD"))
        assertFalse(env.containsKey("PYTHONPATH"))
        assertFalse(env.containsKey("BASH_ENV"))
        assertFalse(env.values.any { it == "s3cr3t" || it == "minio-secret" })

        assertEquals("/home/continuum", env["HOME"])
        assertEquals("C.UTF-8", env["LC_ALL"])
        assertEquals("/cache/huggingface", env["HF_HOME"])
        assertEquals("0", env["CUDA_VISIBLE_DEVICES"])
        assertEquals("/usr/local/cuda/lib64", env["LD_LIBRARY_PATH"])
        assertEquals("http://proxy:3128", env["HTTPS_PROXY"])
        assertEquals("/opt/venv/bin:/usr/bin:/bin", env["PATH"])
        assertEquals("/opt/venv", env["VIRTUAL_ENV"])
        assertEquals("1", env["HF_HUB_ENABLE_HF_TRANSFER"])
    }

    @Test
    fun `hf token goes to env only, never argv or toString`() {
        val token = "hf_supersecret"
        val args = UnslothTrainerCommand.scriptArguments(config(), "/d", "/o")
        val cmd = build(args, mapOf("HF_TOKEN" to token))
        assertEquals(token, cmd.env["HF_TOKEN"])
        assertFalse(cmd.args.any { it.contains(token) })
        assertFalse(cmd.toString().contains(token))
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    fun `real process receives hostile values verbatim and without worker secrets`(@TempDir dir: Path) {
        // Fake venv interpreter that prints each argv element and one env var, NUL-separated
        val bin = Files.createDirectories(dir.resolve("venv/bin"))
        val python = bin.resolve("python")
        Files.writeString(python, "#!/bin/sh\nfor a in \"\$@\"; do printf '%s\\0' \"\$a\"; done\nprintf 'SECRET=%s\\0' \"\$MY_ARBITRARY_SECRET\"\n")
        python.toFile().setExecutable(true)

        val marker = dir.resolve("pwned")
        val payload = listOf("x;touch $marker", "\$(touch $marker)", "`touch $marker`", "a b", "\"")
        val cmd = UnslothTrainerCommand.build(
            dir.resolve("venv").toString(), "train.py", payload,
            mapOf("PATH" to "/usr/bin:/bin", "MY_ARBITRARY_SECRET" to "s3cr3t"), emptyMap(), false
        )
        val process = ProcessBuilder(cmd.args).apply { environment().clear(); environment().putAll(cmd.env) }.start()
        val out = process.inputStream.bufferedReader().readText()
        assertTrue(process.waitFor(10, TimeUnit.SECONDS))

        assertEquals(listOf("train.py") + payload + "SECRET=", out.split('\u0000').dropLast(1))
        assertFalse(Files.exists(marker), "a shell interpreted an argument")
    }
}
