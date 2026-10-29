package com.v2ray.ang.ui.films

data class CCloudMediaItem(
    val id: Int,
    val title: String,
    val description: String = "",
    val year: Int = 0,
    val imdb: Double = 0.0,
    val image: String = "",
    val cover: String = "",
    val isSeries: Boolean = false,
    val genres: List<String> = emptyList(),
    val sources: List<CCloudSourceItem> = emptyList(),
    val seasons: List<CCloudSeasonItem> = emptyList()
)

data class CCloudSourceItem(
    val quality: String,
    val url: String,
    val type: String = "mp4"
)

data class CCloudSeasonItem(
    val seasonNumber: Int,
    val title: String,
    val episodes: List<CCloudEpisodeItem> = emptyList()
)

data class CCloudEpisodeItem(
    val episodeNumber: Int,
    val title: String,
    val description: String = "",
    val image: String = "",
    val sources: List<CCloudSourceItem> = emptyList()
)