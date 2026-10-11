package app.zendiary.ui.nav

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.*
import androidx.navigation.navArgument
import app.zendiary.core.LocalContainer
import app.zendiary.data.settings.AppSettings
import app.zendiary.ui.calendar.CalendarScreen
import app.zendiary.ui.cycle.CycleScreen
import app.zendiary.ui.editor.BreathingScreen
import app.zendiary.ui.editor.EditorScreen
import app.zendiary.ui.home.HomeScreen
import app.zendiary.ui.insights.InsightsScreen
import app.zendiary.ui.search.SearchScreen
import app.zendiary.ui.settings.SettingsScreen
import java.time.LocalDate

private enum class Tab(val route: String, val label: String, val icon: ImageVector) {
    HOME("home", "Journal", Icons.Rounded.AutoStories),
    CALENDAR("calendar", "Calendar", Icons.Rounded.CalendarMonth),
    INSIGHTS("insights", "Insights", Icons.Rounded.Insights),
    CYCLE("cycle", "Cycle", Icons.Rounded.WaterDrop),
    SETTINGS("settings", "Settings", Icons.Rounded.Settings),
}

private const val EDITOR = "editor?id={id}&date={date}&mood={mood}"
private fun editorRoute(id: Long = -1, date: LocalDate = LocalDate.now(), mood: Int = 0) = "editor?id=$id&date=${date.toEpochDay()}&mood=$mood"

@Composable
fun ZenNav() {
    val nav = rememberNavController()
    val settings by LocalContainer.current.settings.flow.collectAsState(AppSettings())
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val tabs = Tab.entries.filter { it != Tab.CYCLE || settings.cycleEnabled }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            if (tabs.any { it.route == route }) NavigationBar {
                tabs.forEach { t ->
                    NavigationBarItem(selected = route == t.route, icon = { Icon(t.icon, t.label) }, label = { Text(t.label) }, onClick = {
                        nav.navigate(t.route) { popUpTo(nav.graph.findStartDestination().id) { saveState = true }; launchSingleTop = true; restoreState = true }
                    })
                }
            }
        },
    ) { pad ->
        NavHost(nav, startDestination = Tab.HOME.route, modifier = Modifier.padding(pad)) {
            composable(Tab.HOME.route) {
                HomeScreen(
                    onOpen = { nav.navigate(editorRoute(id = it)) }, onNew = { nav.navigate(editorRoute(date = it)) },
                    onSearch = { nav.navigate("search") }, onStreaks = { nav.navigate("streaks") },
                )
            }
            composable(Tab.CALENDAR.route) { CalendarScreen({ nav.navigate(editorRoute(id = it)) }, { nav.navigate(editorRoute(date = it)) }) }
            composable(Tab.INSIGHTS.route) { InsightsScreen() }
            composable("streaks") { InsightsScreen(openStreaksFirst = true) }
            composable(Tab.CYCLE.route) { CycleScreen() }
            composable(Tab.SETTINGS.route) { SettingsScreen() }
            composable("search") { SearchScreen({ nav.popBackStack() }) { nav.navigate(editorRoute(id = it)) } }
            composable("breathing") { BreathingScreen { nav.popBackStack() } }
            composable(
                EDITOR,
                arguments = listOf(
                    navArgument("id") { type = NavType.LongType; defaultValue = -1L },
                    navArgument("date") { type = NavType.LongType; defaultValue = LocalDate.now().toEpochDay() },
                    navArgument("mood") { type = NavType.IntType; defaultValue = 0 },
                ),
            ) { be ->
                val a = be.arguments!!
                EditorScreen(
                    entryId = a.getLong("id"), date = LocalDate.ofEpochDay(a.getLong("date")), presetMood = a.getInt("mood"),
                    onClose = { nav.popBackStack() },
                    onSaved = {
                        nav.popBackStack()
                        if (settings.breathing) nav.navigate("breathing")
                    },
                )
            }
        }
    }
}
