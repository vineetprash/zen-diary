package app.zendiary.domain

import java.time.LocalDate

/** One diary entry. `images` are encrypted-store file names; `tags` are activity names. */
data class DiaryEntry(
    val id: Long = 0,
    val uid: String = "",
    val date: LocalDate,
    val minutes: Int,
    val mood: Int = 0,
    val title: String = "",
    val body: String = "",
    val event: String = "",
    val favorite: Boolean = false,
    val images: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
)

data class CycleDay(
    val date: LocalDate,
    val flow: Int,                       // 0 none, 1 light, 2 medium, 3 heavy
    val symptoms: List<String> = emptyList(),
    val note: String = "",
)

data class ExportData(val entries: List<DiaryEntry>, val cycle: List<CycleDay>)
