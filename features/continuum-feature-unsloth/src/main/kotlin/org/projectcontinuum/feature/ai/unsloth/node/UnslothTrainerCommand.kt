package org.projectcontinuum.feature.ai.node

/** Training parameters read from the node properties (defaults applied). The HF token is deliberately not part of it. */
internal data class TrainingConfig(
  val model: String,
  val inputColumn: String,
  val outputColumn: String,
  val systemPrompt: String?,
  val epochs: Int,
  val batchSize: Int,
  val gradientAccumulation: Int,
  val learningRate: Double,
  val maxSeqLength: Int,
  val loraR: Int,
  val loraAlpha: Int,
  val loraDropout: Double,
  val warmupSteps: Int,
  val weightDecay: Double,
  val seed: Int,
  val saveSteps: Int,
  val loggingSteps: Int,
  val use4Bit: Boolean,
  val parquetBatchSize: Int
) {
  companion object {
    fun from(properties: Map<String, Any>?, defaultParquetBatchSize: Int): TrainingConfig {
      fun int(key: String, default: Int) = (properties?.get(key) as? Number)?.toInt() ?: default
      fun double(key: String, default: Double) = (properties?.get(key) as? Number)?.toDouble() ?: default
      return TrainingConfig(
        model = properties?.get("model")?.toString() ?: "unsloth/Phi-4",
        inputColumn = properties?.get("inputColumn")?.toString() ?: "instruction",
        outputColumn = properties?.get("outputColumn")?.toString() ?: "response",
        systemPrompt = properties?.get("systemPrompt")?.toString(),
        epochs = int("epochs", 1),
        batchSize = int("batchSize", 2),
        gradientAccumulation = int("gradientAccumulation", 4),
        learningRate = double("learningRate", 0.0002),
        maxSeqLength = int("maxSeqLength", 2048),
        loraR = int("loraR", 16),
        loraAlpha = int("loraAlpha", 16),
        loraDropout = double("loraDropout", 0.0),
        warmupSteps = int("warmupSteps", 5),
        weightDecay = double("weightDecay", 0.01),
        seed = int("seed", 42),
        saveSteps = int("saveSteps", 100),
        loggingSteps = int("loggingSteps", 10),
        use4Bit = properties?.get("use4Bit") as? Boolean ?: true,
        parquetBatchSize = int("parquetBatchSize", defaultParquetBatchSize)
      )
    }
  }
}

/** The trainer process: the exact argv (no shell) and its complete environment. */
internal data class TrainerCommand(val args: List<String>, val env: Map<String, String>) {
  /** Env values (HF_TOKEN) are never printed. */
  override fun toString(): String = "TrainerCommand(args=$args, env=${env.keys})"
}

/**
 * Builds the `train.py` invocation. Pure (no I/O), so it is unit-tested.
 *
 * No shell is involved: the venv's interpreter is run directly with one argv element per argument, so
 * property values (model id, column names, system prompt) reach `argparse` verbatim and are never parsed
 * by bash/cmd. "Activating" the venv is reproduced by the absolute interpreter path plus `VIRTUAL_ENV`
 * and the venv's bin dir first on `PATH`.
 *
 * The environment is an allow-list of worker variables (the worker env holds MinIO/database/Continuum
 * credentials, and `train.py` loads model code from HuggingFace with `trust_remote_code=True`), plus the
 * trainer's own vars. Secrets such as `HF_TOKEN` travel only via [TrainerCommand.env], never argv.
 */
internal object UnslothTrainerCommand {

  private val ENV_ALLOW_EXACT = setOf(
    // process basics
    "PATH", "HOME", "USER", "LOGNAME", "SHELL", "LANG", "LANGUAGE", "TZ", "TERM", "TMPDIR", "TEMP", "TMP",
    // proxies and CA bundles (model downloads)
    "HTTP_PROXY", "HTTPS_PROXY", "NO_PROXY", "ALL_PROXY",
    "SSL_CERT_FILE", "SSL_CERT_DIR", "REQUESTS_CA_BUNDLE", "CURL_CA_BUNDLE",
    // native libraries (CUDA) and caches
    "LD_LIBRARY_PATH", "XDG_CACHE_HOME",
    // Python runtime knobs (PYTHONHOME / PYTHONPATH are deliberately excluded: they would break venv isolation)
    "PYTHONUNBUFFERED", "PYTHONIOENCODING", "PYTHONUTF8",
    // CPU threading / tokenizers
    "OMP_NUM_THREADS", "MKL_NUM_THREADS", "TOKENIZERS_PARALLELISM",
    // Windows essentials (Python needs SYSTEMROOT for sockets/random)
    "SYSTEMROOT", "WINDIR", "COMSPEC", "PATHEXT", "USERPROFILE", "USERNAME", "APPDATA", "LOCALAPPDATA",
    "PROGRAMDATA", "NUMBER_OF_PROCESSORS", "PROCESSOR_ARCHITECTURE"
  )

  /** HuggingFace (`HF_HOME`, `HF_TOKEN`, `HF_ENDPOINT`, …), CUDA/NVIDIA, PyTorch/Triton/Unsloth config, locale. */
  private val ENV_ALLOW_PREFIXES = listOf(
    "LC_", "HF_", "HUGGINGFACE_", "TRANSFORMERS_", "CUDA_", "NVIDIA_", "TORCH_", "PYTORCH_", "TRITON_", "UNSLOTH_"
  )

  /** Environment variable names are case-insensitive on Windows (`Path`), so match upper-cased. */
  internal fun isAllowed(name: String): Boolean {
    val upper = name.uppercase()
    return upper in ENV_ALLOW_EXACT || ENV_ALLOW_PREFIXES.any { upper.startsWith(it) }
  }

  /** `train.py` arguments for [config]; every value is exactly one element. */
  fun scriptArguments(config: TrainingConfig, dataFilePath: String, outputPath: String): List<String> {
    val args = mutableListOf(
      "--data", dataFilePath,
      "--model", config.model,
      "--output", outputPath,
      "--input-column", config.inputColumn,
      "--output-column", config.outputColumn,
      "--epochs", config.epochs.toString(),
      "--batch-size", config.batchSize.toString(),
      "--gradient-accumulation", config.gradientAccumulation.toString(),
      "--learning-rate", config.learningRate.toString(),
      "--max-seq-length", config.maxSeqLength.toString(),
      "--lora-r", config.loraR.toString(),
      "--lora-alpha", config.loraAlpha.toString(),
      "--lora-dropout", config.loraDropout.toString(),
      "--warmup-steps", config.warmupSteps.toString(),
      "--weight-decay", config.weightDecay.toString(),
      "--seed", config.seed.toString(),
      "--save-steps", config.saveSteps.toString(),
      "--logging-steps", config.loggingSteps.toString(),
      "--parquet-batch-size", config.parquetBatchSize.toString(),
      "--silent",  // Suppress non-JSON output for cleaner IPC parsing
      "--ipc"  // Enable IPC mode for progress reporting
    )
    if (!config.systemPrompt.isNullOrBlank()) {
      args += listOf("--system-prompt", config.systemPrompt)
    }
    if (!config.use4Bit) {
      args += "--no-4bit"
    }
    return args
  }

  /**
   * @param inherited the worker environment (normally `System.getenv()`), filtered to the allow-list
   * @param extraEnv trainer-specific vars (e.g. `HF_TOKEN` from the node property); override inherited ones
   */
  fun build(
    venvPath: String,
    scriptPath: String,
    scriptArgs: List<String>,
    inherited: Map<String, String>,
    extraEnv: Map<String, String>,
    isWindows: Boolean
  ): TrainerCommand {
    val separator = if (isWindows) "\\" else "/"
    val binDir = if (isWindows) "$venvPath${separator}Scripts" else "$venvPath${separator}bin"
    val python = if (isWindows) "$binDir${separator}python.exe" else "$binDir${separator}python"

    val env = LinkedHashMap<String, String>()
    inherited.filterKeys(::isAllowed).forEach { (k, v) -> env[k] = v }
    // What `source <venv>/bin/activate` did: VIRTUAL_ENV + venv bin dir first on PATH (for any tool train.py spawns)
    val pathKey = env.keys.firstOrNull { it.equals("PATH", ignoreCase = true) } ?: "PATH"
    val inheritedPath = env[pathKey]
    env[pathKey] = if (inheritedPath.isNullOrEmpty()) binDir else "$binDir${if (isWindows) ";" else ":"}$inheritedPath"
    env["VIRTUAL_ENV"] = venvPath
    // Faster HuggingFace downloads (Rust-based parallel chunked transfers)
    env["HF_HUB_ENABLE_HF_TRANSFER"] = "1"
    env.putAll(extraEnv)

    return TrainerCommand(listOf(python, scriptPath) + scriptArgs, env)
  }

  fun isWindows(): Boolean = System.getProperty("os.name").lowercase().contains("windows")
}
