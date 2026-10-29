package com.v2ray.ang.ui.films

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Typeface
import android.util.TypedValue
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.v2ray.ang.R
import com.v2ray.ang.ui.base.HelperBaseComponentActivity
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.time.Duration.Companion.milliseconds

enum class FilmSortOption(val label: String, val apiOrder: String) {
    LATEST("جدیدترین", "created"),
    TOP_IMDB("امتیاز IMDB", "imdb"),
    NEWEST_YEAR("سال ساخت", "year"),
    TITLE_AZ("حروف الفبا", "title")
}

class FilmsActivity : HelperBaseComponentActivity() {

    @Composable
    override fun ScreenContent() {
        FilmsMainScreen(onBack = { finish() })
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilmsMainScreen(onBack: () -> Unit) {
    val neonGreen = Color(0xFFC6F044)
    val darkCard = Color(0xFF141814)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val gridState = rememberLazyGridState()

    var moviesList by remember { mutableStateOf<List<CCloudMediaItem>>(emptyList()) }
    var seriesList by remember { mutableStateOf<List<CCloudMediaItem>>(emptyList()) }
    var moviesPage by remember { mutableIntStateOf(0) }
    var seriesPage by remember { mutableIntStateOf(0) }
    var hasMoreMovies by remember { mutableStateOf(true) }
    var hasMoreSeries by remember { mutableStateOf(true) }

    var isLoading by remember { mutableStateOf(true) }
    var isLoadingMore by remember { mutableStateOf(false) }

    // وضعیت جستجوی مستقیم از سرور
    var isSearchExpanded by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var isSearchingServer by remember { mutableStateOf(false) }
    var searchResults by remember { mutableStateOf<List<CCloudMediaItem>?>(null) }

    // تب اصلی: 0 = Movies ، 1 = Series
    var selectedCategoryTab by remember { mutableIntStateOf(0) }

    // وضعیت فیلترها
    var availableGenres by remember {
        mutableStateOf(listOf(CCloudGenre(id = 0, title = "All")))
    }
    var selectedGenre by remember { mutableStateOf(CCloudGenre(id = 0, title = "All")) }
    var selectedYearFilter by remember { mutableStateOf("All") }
    var selectedSort by remember { mutableStateOf(FilmSortOption.LATEST) }

    var selectedMedia by remember { mutableStateOf<CCloudMediaItem?>(null) }
    var playingSource by remember { mutableStateOf<Pair<String, String>?>(null) }

    // دریافت لیست کامل ژانرها در پس‌زمینه
    LaunchedEffect(Unit) {
        val fetchedGenres = CCloudRepository.fetchAllGenres()
        if (fetchedGenres.size > 1) {
            availableGenres = fetchedGenres
        }
    }

    // بارگذاری اولیه (بدون فراخوانی scrollToItem قبل از پایان لودینگ)
    fun resetAndLoadInitial() {
        scope.launch {
            isLoading = true
            moviesPage = 0
            seriesPage = 0
            hasMoreMovies = true
            hasMoreSeries = true
            try {
                val (mFetched, sFetched) = coroutineScope {
                    val mDeferred = async {
                        CCloudRepository.fetchMoviesPage(
                            genreId = selectedGenre.id,
                            order = selectedSort.apiOrder,
                            page = 0
                        )
                    }
                    val sDeferred = async {
                        CCloudRepository.fetchSeriesPage(
                            genreId = selectedGenre.id,
                            order = selectedSort.apiOrder,
                            page = 0
                        )
                    }
                    mDeferred.await() to sDeferred.await()
                }

                moviesList = mFetched
                seriesList = sFetched
                moviesPage = 1
                seriesPage = 1
                hasMoreMovies = mFetched.isNotEmpty()
                hasMoreSeries = sFetched.isNotEmpty()

                val updatedGenres = CCloudRepository.getCachedGenres()
                if (updatedGenres.size > 1) {
                    availableGenres = updatedGenres
                }
            } catch (_: Exception) {
                moviesList = emptyList()
                seriesList = emptyList()
            } finally {
                isLoading = false
            }
        }
    }

    // دانلود خودکار صفحه بعدی هنگام رسیدن اسکرول به یک ردیف مانده به آخر
    fun loadNextPageAutomatically() {
        if (isLoading || isLoadingMore || searchQuery.trim().length >= 2) return
        val isSeriesTab = selectedCategoryTab == 1
        if (isSeriesTab && !hasMoreSeries) return
        if (!isSeriesTab && !hasMoreMovies) return

        scope.launch {
            isLoadingMore = true
            try {
                if (isSeriesTab) {
                    val nextItems = CCloudRepository.fetchSeriesPage(
                        genreId = selectedGenre.id,
                        order = selectedSort.apiOrder,
                        page = seriesPage
                    )
                    if (nextItems.isNotEmpty()) {
                        val beforeSize = seriesList.size
                        seriesList = (seriesList + nextItems).distinctBy { it.id }
                        seriesPage += 1
                        hasMoreSeries = seriesList.size > beforeSize
                    } else {
                        hasMoreSeries = false
                    }
                } else {
                    val nextItems = CCloudRepository.fetchMoviesPage(
                        genreId = selectedGenre.id,
                        order = selectedSort.apiOrder,
                        page = moviesPage
                    )
                    if (nextItems.isNotEmpty()) {
                        val beforeSize = moviesList.size
                        moviesList = (moviesList + nextItems).distinctBy { it.id }
                        moviesPage += 1
                        hasMoreMovies = moviesList.size > beforeSize
                    } else {
                        hasMoreMovies = false
                    }
                }
                val updatedGenres = CCloudRepository.getCachedGenres()
                if (updatedGenres.size > availableGenres.size) {
                    availableGenres = updatedGenres
                }
            } catch (_: Exception) {
            } finally {
                isLoadingMore = false
            }
        }
    }

    // بارگذاری از سرور هنگام تغییر Sort یا Genre
    LaunchedEffect(selectedSort, selectedGenre) {
        resetAndLoadInitial()
    }

    // جستجوی مستقیم روی سرور با مدیریت ایمن وضعیت لودینگ
    LaunchedEffect(searchQuery) {
        val q = searchQuery.trim()
        if (q.length >= 2) {
            isSearchingServer = true
            try {
                delay(400.milliseconds)
                val remoteResults = CCloudRepository.searchMedia(q)
                val localMatches = (moviesList + seriesList).filter {
                    it.title.contains(q, ignoreCase = true) ||
                            it.description.contains(q, ignoreCase = true)
                }
                val combined = (remoteResults + localMatches).distinctBy { "${it.isSeries}_${it.id}" }
                searchResults = combined

                val wantSeries = selectedCategoryTab == 1
                if (combined.isNotEmpty() && combined.none { it.isSeries == wantSeries }) {
                    selectedCategoryTab = if (wantSeries) 0 else 1
                }
            } catch (_: Exception) {
            } finally {
                isSearchingServer = false
            }
        } else {
            searchResults = null
            isSearchingServer = false
        }
    }

    // نمایش پلیر تمام‌صفحه افقی در صورت پخش ویدیو
    if (playingSource != null) {
        val (videoTitle, videoUrl) = playingSource!!
        BackHandler { playingSource = null }
        InternalExoPlayerScreen(
            title = videoTitle,
            videoUrl = videoUrl,
            onOpenExternal = { url ->
                try {
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(url.toUri(), "video/*")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(Intent.createChooser(intent, "Play with External Player"))
                } catch (_: Exception) {
                    Toast.makeText(context, "No external video player found", Toast.LENGTH_SHORT).show()
                }
            },
            onClose = { playingSource = null }
        )
        return
    }

    val isSearchMode = searchQuery.trim().length >= 2
    val yearFilters = listOf("All", "2026", "2025", "2024", "2023", "2020+", "2010-2019", "Classic (<2010)")

    val rawTabItems = remember(moviesList, seriesList, searchResults, isSearchMode, selectedCategoryTab) {
        val wantSeries = selectedCategoryTab == 1
        if (isSearchMode) {
            (searchResults ?: emptyList()).filter { it.isSeries == wantSeries }
        } else {
            if (wantSeries) seriesList else moviesList
        }
    }

    val filteredAndSortedList = remember(
        rawTabItems,
        isSearchMode,
        selectedGenre,
        selectedYearFilter,
        selectedSort
    ) {
        val filtered = if (isSearchMode) {
            rawTabItems
        } else {
            rawTabItems.filter { item ->
                val matchesGenre = selectedGenre.id == 0 ||
                        item.genres.isEmpty() ||
                        item.genres.any { it.equals(selectedGenre.title, ignoreCase = true) }

                val matchesYear = when (selectedYearFilter) {
                    "All" -> true
                    "2026" -> item.year == 2026
                    "2025" -> item.year == 2025
                    "2024" -> item.year == 2024
                    "2023" -> item.year == 2023
                    "2020+" -> item.year >= 2020
                    "2010-2019" -> item.year in 2010..2019
                    "Classic (<2010)" -> item.year in 1900..2009
                    else -> true
                }

                matchesGenre && matchesYear
            }
        }

        when (selectedSort) {
            FilmSortOption.LATEST -> filtered
            FilmSortOption.TOP_IMDB -> filtered.sortedByDescending { it.imdb }
            FilmSortOption.NEWEST_YEAR -> filtered.sortedByDescending { it.year }
            FilmSortOption.TITLE_AZ -> filtered.sortedBy { it.title.lowercase(Locale.ROOT) }
        }
    }

    // تشخیص رسیدن اسکرول به یک ردیف مانده به انتهای لیست برای دانلود خودکار
    val shouldLoadMore by remember {
        derivedStateOf {
            val layoutInfo = gridState.layoutInfo
            val totalItems = layoutInfo.totalItemsCount
            if (totalItems < 6) return@derivedStateOf false
            val visibleItems = layoutInfo.visibleItemsInfo
            val lastVisible = visibleItems.lastOrNull() ?: return@derivedStateOf false
            val columnsInRow = visibleItems.count { it.row == lastVisible.row }.coerceAtLeast(3)
            lastVisible.index >= (totalItems - (columnsInRow * 2)).coerceAtLeast(1)
        }
    }

    LaunchedEffect(shouldLoadMore, filteredAndSortedList.size, selectedCategoryTab) {
        if (shouldLoadMore && !isLoading && !isLoadingMore && !isSearchMode) {
            loadNextPageAutomatically()
        }
    }

    val moviesCount = if (isSearchMode) {
        (searchResults ?: emptyList()).count { !it.isSeries }
    } else {
        moviesList.size
    }
    val seriesCount = if (isSearchMode) {
        (searchResults ?: emptyList()).count { it.isSeries }
    } else {
        seriesList.size
    }
    val sortOptionsList = remember { FilmSortOption.entries.toList() }

    Scaffold(
        containerColor = Color.Black
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(Color.Black),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // ۱. هدر بالای صفحه
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) {
                        Icon(
                            painter = painterResource(R.drawable.ic_arrow_back_24dp),
                            contentDescription = "Back",
                            tint = Color.White
                        )
                    }
                    Text(
                        text = "Films & Series",
                        color = neonGreen,
                        fontSize = 19.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(
                        onClick = {
                            isSearchExpanded = !isSearchExpanded
                            if (!isSearchExpanded) {
                                searchQuery = ""
                            }
                        }
                    ) {
                        Text(
                            text = if (isSearchExpanded) "✕ بستن جستجو" else "🔍 جستجو (Search)",
                            color = if (isSearchExpanded) neonGreen else Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    IconButton(onClick = { resetAndLoadInitial() }) {
                        Icon(
                            painter = painterResource(R.drawable.ic_check_update_24dp),
                            contentDescription = "Refresh",
                            tint = Color.White
                        )
                    }
                }
            }

            // ۲. کادر جستجوی بازشونده
            AnimatedVisibility(visible = isSearchExpanded) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { newValue: String -> searchQuery = newValue },
                    placeholder = {
                        Text(
                            text = "نام فیلم یا سریال را وارد کنید...",
                            color = Color.Gray,
                            fontSize = 13.sp
                        )
                    },
                    leadingIcon = {
                        Text(
                            text = "🔍",
                            fontSize = 15.sp,
                            modifier = Modifier.padding(start = 4.dp)
                        )
                    },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            TextButton(onClick = { searchQuery = "" }) {
                                Text("✕", color = Color.Gray, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = neonGreen,
                        unfocusedBorderColor = Color(0xFF263026),
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedContainerColor = darkCard,
                        unfocusedContainerColor = darkCard
                    ),
                    modifier = Modifier
                        .widthIn(max = 600.dp)
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }

            // ۳. سوییچ بین Movies و Series
            Row(
                modifier = Modifier
                    .widthIn(max = 600.dp)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(darkCard)
                    .border(1.dp, Color(0xFF252E25), RoundedCornerShape(12.dp))
                    .padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                val tabs = listOf(
                    "🎬 Movies ($moviesCount)" to 0,
                    "📺 Series ($seriesCount)" to 1
                )
                tabs.forEach { (title, index) ->
                    val isSelected = selectedCategoryTab == index
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(9.dp))
                            .background(if (isSelected) neonGreen else Color.Transparent)
                            .clickable {
                                selectedCategoryTab = index
                            }
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = title,
                            color = if (isSelected) Color.Black else Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }

            // ۴. منوهای کشویی فیلتر (Sort, Genre, Year)
            Row(
                modifier = Modifier
                    .widthIn(max = 600.dp)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilterDropdownBox(
                    title = "Sort",
                    selectedValue = selectedSort.label,
                    options = sortOptionsList.map { it.label },
                    onSelect = { chosenLabel ->
                        sortOptionsList.firstOrNull { it.label == chosenLabel }?.let {
                            selectedSort = it
                        }
                    },
                    modifier = Modifier.weight(1f)
                )

                FilterDropdownBox(
                    title = "Genre",
                    selectedValue = selectedGenre.title,
                    options = availableGenres.map { it.title },
                    onSelect = { chosenTitle ->
                        availableGenres.firstOrNull { it.title == chosenTitle }?.let {
                            selectedGenre = it
                        }
                    },
                    modifier = Modifier.weight(1f)
                )

                FilterDropdownBox(
                    title = "Year",
                    selectedValue = selectedYearFilter,
                    options = yearFilters,
                    onSelect = { chosenYear ->
                        selectedYearFilter = chosenYear
                    },
                    modifier = Modifier.weight(1f)
                )
            }

            // ۵. نمایش شبکه‌ای تطبیقی (Adaptive Grid) با بارگذاری خودکار هنگام اسکرول
            Box(modifier = Modifier.fillMaxSize()) {
                when {
                    isLoading || isSearchingServer -> {
                        Column(
                            modifier = Modifier.align(Alignment.Center),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            CircularProgressIndicator(color = neonGreen)
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = if (isSearchingServer) "در حال جستجو در سرور..." else "در حال دریافت فیلم و سریال...",
                                color = Color.Gray,
                                fontSize = 14.sp
                            )
                        }
                    }

                    filteredAndSortedList.isEmpty() -> {
                        Column(
                            modifier = Modifier
                                .align(Alignment.Center)
                                .padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = if (moviesList.isEmpty() && seriesList.isEmpty() && !isSearchMode) {
                                    "ارتباط با سرور برقرار نشد. اینترنت خود را بررسی کرده و دوباره تلاش کنید."
                                } else {
                                    "موردی با این مشخصات یافت نشد."
                                },
                                color = Color.Gray,
                                fontSize = 14.sp,
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Button(
                                onClick = {
                                    searchQuery = ""
                                    selectedGenre = CCloudGenre(id = 0, title = "All")
                                    selectedYearFilter = "All"
                                    resetAndLoadInitial()
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = neonGreen)
                            ) {
                                Text("تلاش مجدد / پاک کردن فیلترها", color = Color.Black, fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    else -> {
                        LazyVerticalGrid(
                            state = gridState,
                            columns = GridCells.Adaptive(minSize = 115.dp),
                            contentPadding = PaddingValues(12.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            itemsIndexed(
                                items = filteredAndSortedList,
                                key = { index: Int, item: CCloudMediaItem -> "${index}_${item.id}_${item.title}" }
                            ) { _: Int, item: CCloudMediaItem ->
                                FilmPosterCard(
                                    item = item,
                                    onClick = { selectedMedia = item }
                                )
                            }

                            if (isLoadingMore) {
                                item(span = { GridItemSpan(maxLineSpan) }) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 12.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        CircularProgressIndicator(
                                            color = neonGreen,
                                            modifier = Modifier.size(26.dp),
                                            strokeWidth = 2.5.dp
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // شیت جزئیات فیلم یا سریال
    if (selectedMedia != null) {
        val media = selectedMedia!!
        ModalBottomSheet(
            onDismissRequest = { selectedMedia = null },
            containerColor = Color(0xFF141814)
        ) {
            MediaDetailsSheetContent(
                media = media,
                onPlayInternal = { title, url ->
                    selectedMedia = null
                    playingSource = title to url
                },
                onPlayExternal = { url ->
                    try {
                        val intent = Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(CCloudRepository.sanitizeStreamUrl(url).toUri(), "video/*")
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(Intent.createChooser(intent, "Play with External Player"))
                    } catch (_: Exception) {
                        Toast.makeText(context, "No external video player found", Toast.LENGTH_SHORT).show()
                    }
                }
            )
        }
    }
}

@Composable
fun FilterDropdownBox(
    title: String,
    selectedValue: String,
    options: List<String>,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val neonGreen = Color(0xFFC6F044)
    val darkCard = Color(0xFF141814)
    var expanded by remember { mutableStateOf(false) }
    val isFiltered = selectedValue != "All" && title != "Sort"

    Box(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(darkCard)
                .border(
                    width = 1.dp,
                    color = if (expanded || isFiltered) neonGreen else Color(0xFF283228),
                    shape = RoundedCornerShape(10.dp)
                )
                .clickable { expanded = true }
                .padding(horizontal = 10.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "$title: $selectedValue",
                color = if (isFiltered) neonGreen else Color.White,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = if (expanded) "▴" else "▾",
                color = neonGreen,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier
                .background(Color(0xFF1B211B))
                .heightIn(max = 320.dp)
        ) {
            options.forEach { option ->
                val isSelected = option == selectedValue
                DropdownMenuItem(
                    text = {
                        Text(
                            text = option,
                            color = if (isSelected) neonGreen else Color.White,
                            fontSize = 13.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                        )
                    },
                    onClick = {
                        onSelect(option)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
fun FilmPosterCard(item: CCloudMediaItem, onClick: () -> Unit) {
    val neonGreen = Color(0xFFC6F044)
    Column(
        modifier = Modifier
            .widthIn(max = 150.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFF151915))
            .border(1.dp, Color(0xFF263026), RoundedCornerShape(10.dp))
            .clickable { onClick() }
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.68f),
            contentAlignment = Alignment.Center
        ) {
            if (item.image.isNotBlank()) {
                AsyncImage(
                    model = item.image,
                    contentDescription = item.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Text(
                    text = if (item.isSeries) "📺" else "🎬",
                    fontSize = 28.sp
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f))
                        )
                    )
            )

            if (item.imdb > 0) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(5.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .background(Color.Black.copy(alpha = 0.78f))
                        .padding(horizontal = 4.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = "★ " + String.format(Locale.US, "%.1f", item.imdb),
                        color = neonGreen,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            if (item.year > 0) {
                Text(
                    text = item.year.toString(),
                    color = neonGreen,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(5.dp)
                )
            }
        }

        Text(
            text = item.title,
            color = Color.White,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 5.dp)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaDetailsSheetContent(
    media: CCloudMediaItem,
    onPlayInternal: (String, String) -> Unit,
    onPlayExternal: (String) -> Unit
) {
    val neonGreen = Color(0xFFC6F044)
    var selectedSeasonIndex by remember { mutableIntStateOf(0) }
    var seasonsList by remember(media) { mutableStateOf(media.seasons) }
    var loadingEpisodes by remember { mutableStateOf(false) }

    LaunchedEffect(media.id, media.isSeries) {
        if (media.isSeries && media.id > 0) {
            loadingEpisodes = true
            val fetched = CCloudRepository.fetchSeriesSeasons(media.id)
            if (fetched.isNotEmpty()) {
                seasonsList = fetched
            }
            loadingEpisodes = false
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(width = 95.dp, height = 135.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFF1F261F)),
                    contentAlignment = Alignment.Center
                ) {
                    if (media.image.isNotBlank()) {
                        AsyncImage(
                            model = media.image,
                            contentDescription = media.title,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Text(text = if (media.isSeries) "📺" else "🎬", fontSize = 30.sp)
                    }
                }
                Spacer(modifier = Modifier.width(16.dp))
                Column {
                    Text(
                        text = media.title,
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = if (media.isSeries) "TV Series" else "Movie",
                        color = neonGreen,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                    if (media.year > 0) {
                        Text(
                            text = "Release Year: ${media.year}",
                            color = Color.Gray,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                    if (media.imdb > 0) {
                        Text(
                            text = "IMDB Rating: ★ ${String.format(Locale.US, "%.1f", media.imdb)}",
                            color = neonGreen,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                    if (media.genres.isNotEmpty()) {
                        Text(
                            text = media.genres.joinToString(" • "),
                            color = Color.LightGray,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            }
        }

        if (media.description.isNotEmpty()) {
            item {
                Text(
                    text = media.description,
                    color = Color(0xFFCCCCCC),
                    fontSize = 13.sp,
                    lineHeight = 19.sp
                )
            }
        }

        if (media.sources.isNotEmpty()) {
            item {
                Text(
                    text = "انتخاب کیفیت و پخش (Select Quality)",
                    color = neonGreen,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            items(items = media.sources) { source: CCloudSourceItem ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = { onPlayInternal("${media.title} (${source.quality})", source.url) },
                        colors = ButtonDefaults.buttonColors(containerColor = neonGreen),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("▶ Play ${source.quality}", color = Color.Black, fontWeight = FontWeight.Bold)
                    }
                    OutlinedButton(
                        onClick = { onPlayExternal(source.url) }
                    ) {
                        Text("External", color = neonGreen, fontSize = 12.sp)
                    }
                }
            }
        }

        if (media.isSeries) {
            if (loadingEpisodes) {
                item {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        CircularProgressIndicator(color = neonGreen, modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text("در حال دریافت فصل‌ها و قسمت‌ها...", color = Color.Gray, fontSize = 13.sp)
                    }
                }
            }

            if (seasonsList.isNotEmpty()) {
                item {
                    Text(
                        text = "Seasons (${seasonsList.size})",
                        color = neonGreen,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(count = seasonsList.size) { index: Int ->
                            val season = seasonsList[index]
                            val selected = selectedSeasonIndex == index
                            FilterChip(
                                selected = selected,
                                onClick = { selectedSeasonIndex = index },
                                label = {
                                    Text(
                                        text = season.title,
                                        color = if (selected) Color.Black else Color.White,
                                        fontWeight = FontWeight.Bold
                                    )
                                },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = neonGreen,
                                    containerColor = Color(0xFF1F261F)
                                )
                            )
                        }
                    }
                }

                val currentSeason = seasonsList.getOrNull(selectedSeasonIndex) ?: seasonsList.firstOrNull()
                if (currentSeason != null) {
                    items(items = currentSeason.episodes) { episode: CCloudEpisodeItem ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color(0xFF1F261F))
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            Text(
                                text = episode.title,
                                color = Color.White,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(items = episode.sources) { epSource: CCloudSourceItem ->
                                    Button(
                                        onClick = {
                                            onPlayInternal(
                                                "${media.title} - ${episode.title} (${epSource.quality})",
                                                epSource.url
                                            )
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = neonGreen),
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                        modifier = Modifier.height(34.dp)
                                    ) {
                                        Text(
                                            text = "▶ ${epSource.quality}",
                                            color = Color.Black,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                    OutlinedButton(
                                        onClick = { onPlayExternal(epSource.url) },
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                        modifier = Modifier.height(34.dp)
                                    ) {
                                        Text("External", color = neonGreen, fontSize = 11.sp)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        item { Spacer(modifier = Modifier.height(24.dp)) }
    }
}

/**
 * تنظیم استایل زیرنویس ExoPlayer: حذف پس‌زمینه مشکی و اعمال حاشیه (Outline) مشکی دور متن
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
private fun applySubtitleOutlineStyle(playerView: PlayerView) {
    playerView.subtitleView?.apply {
        setApplyEmbeddedStyles(false)
        setApplyEmbeddedFontSizes(false)
        setStyle(
            CaptionStyleCompat(
                android.graphics.Color.WHITE,             // رنگ متن زیرنویس (سفید)
                android.graphics.Color.TRANSPARENT,       // حذف کامل پس‌زمینه مشکی متن
                android.graphics.Color.TRANSPARENT,       // حذف کامل کادر پنجره زیرنویس
                CaptionStyleCompat.EDGE_TYPE_OUTLINE,     // افکت خط دور متن (Text Outline)
                android.graphics.Color.BLACK,             // رنگ خط دور متن: مشکی
                Typeface.DEFAULT_BOLD
            )
        )
        setFixedTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
        setBottomPaddingFraction(0.06f)
    }
}

@SuppressLint("SourceLockedOrientationActivity")
@Composable
fun InternalExoPlayerScreen(
    title: String,
    videoUrl: String,
    onOpenExternal: (String) -> Unit,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val neonGreen = Color(0xFFC6F044)
    var isBuffering by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val exoPlayer = remember {
        ExoPlayer.Builder(context).build()
    }

    LaunchedEffect(videoUrl) {
        isBuffering = true
        errorMessage = null
        val finalUrl = CCloudRepository.resolvePlayableStreamUrl(videoUrl)
        runCatching {
            exoPlayer.setMediaItem(MediaItem.fromUri(finalUrl.toUri()))
            exoPlayer.prepare()
            exoPlayer.playWhenReady = true
        }.onFailure {
            isBuffering = false
            errorMessage = "خطا در آماده‌سازی لینک ویدیو"
        }
    }

    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                isBuffering = playbackState == Player.STATE_BUFFERING
                if (playbackState == Player.STATE_READY) {
                    errorMessage = null
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                isBuffering = false
                errorMessage = "خطا در پخش مستقیم این لینک (${error.errorCodeName})"
            }
        }
        exoPlayer.addListener(listener)
        onDispose {
            exoPlayer.removeListener(listener)
        }
    }

    DisposableEffect(Unit) {
        val activity = context.findActivity()
        val originalOrientation = activity?.requestedOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE

        val window = activity?.window
        val insetsController = if (window != null) {
            WindowCompat.getInsetsController(window, window.decorView)
        } else null

        insetsController?.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        insetsController?.hide(WindowInsetsCompat.Type.systemBars())

        onDispose {
            activity?.requestedOrientation = originalOrientation
            insetsController?.show(WindowInsetsCompat.Type.systemBars())
            exoPlayer.release()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = exoPlayer
                    useController = true
                    keepScreenOn = true
                    applySubtitleOutlineStyle(this)
                }
            },
            update = { view ->
                view.player = exoPlayer
                applySubtitleOutlineStyle(view)
            },
            modifier = Modifier.fillMaxSize()
        )

        if (isBuffering && errorMessage == null) {
            CircularProgressIndicator(
                color = neonGreen,
                modifier = Modifier.align(Alignment.Center)
            )
        }

        if (errorMessage != null) {
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0xDD141814))
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = errorMessage!!,
                    color = Color.White,
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = {
                            errorMessage = null
                            isBuffering = true
                            exoPlayer.prepare()
                            exoPlayer.play()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = neonGreen)
                    ) {
                        Text("تلاش مجدد", color = Color.Black, fontWeight = FontWeight.Bold)
                    }
                    OutlinedButton(onClick = { onOpenExternal(CCloudRepository.sanitizeStreamUrl(videoUrl)) }) {
                        Text("پخش در MX / VLC", color = neonGreen)
                    }
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.50f))
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .align(Alignment.TopCenter),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onClose,
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.6f))
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_arrow_back_24dp),
                    contentDescription = "Close Player",
                    tint = Color.White
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = title,
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        }
    }
}