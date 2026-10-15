package dev.localai.chat.llm

sealed interface ModelState {
    data object NotLoaded : ModelState
    data object Loading : ModelState
    data class Loaded(val modelName: String) : ModelState
    data class Error(val message: String) : ModelState
}

sealed interface ImportState {
    data object Idle : ImportState
    data class Importing(val fileName: String) : ImportState
}
