package com.example.awake.ui.navigation

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.awake.AppContainer
import com.example.awake.domain.usecase.ImportTimetableUseCase
import com.example.awake.domain.usecase.LoginUseCase
import com.example.awake.domain.usecase.ObserveTimetableUseCase
import com.example.awake.domain.usecase.RefreshTimetableUseCase
import com.example.awake.ui.auth.AuthScreen
import com.example.awake.ui.auth.AuthViewModel
import com.example.awake.ui.auth.AuthViewModelFactory
import com.example.awake.ui.auth.JnuLoginScreen
import com.example.awake.ui.importterm.TermImportScreen
import com.example.awake.ui.importterm.TermImportViewModel
import com.example.awake.ui.importterm.TermImportViewModelFactory
import com.example.awake.ui.school.SchoolPickerScreen
import com.example.awake.ui.settings.SettingsScreen
import com.example.awake.ui.timetable.CourseDetailScreen
import com.example.awake.ui.timetable.CourseDetailViewModel
import com.example.awake.ui.timetable.CourseDetailViewModelFactory
import com.example.awake.ui.timetable.CourseEditorScreen
import com.example.awake.ui.timetable.CourseEditorViewModel
import com.example.awake.ui.timetable.CourseEditorViewModelFactory
import com.example.awake.ui.timetable.TimetableScreen
import com.example.awake.ui.timetable.TimetableViewModel
import com.example.awake.ui.timetable.TimetableViewModelFactory

@Composable
fun AppNavHost(container: AppContainer) {
    val navController = rememberNavController()
    val observe = ObserveTimetableUseCase(container.localRepository)
    val refresh = RefreshTimetableUseCase(container.scheduleRouter)
    val importer = ImportTimetableUseCase(container.localRepository, container.scheduleRouter)
    val login = LoginUseCase(container.authRepository, container.localRepository)
    val timetableVm: TimetableViewModel = viewModel(factory = TimetableViewModelFactory(
        observe, refresh, container.localRepository, container.reminderCoordinator,
        container.timetableSelectionStore, container.timetableDisplaySettingsStore, container.scheduleRouter,
        container.jsonTimetableStore
    ))
    NavHost(navController = navController, startDestination = Routes.TIMETABLE) {
        composable(Routes.TIMETABLE) {
            TimetableScreen(
                viewModel = timetableVm,
                auth = container.authRepository,
                jnuAuth = container.jnuAuthRepository,
                onLogin = { school -> navController.navigate(Routes.login(school, "timetable")) },
                onImportAdd = { navController.navigate(Routes.schoolPicker("add")) },
                onImportOverwrite = { navController.navigate(Routes.schoolPicker("overwrite")) },
                onSettings = { navController.navigate(Routes.SETTINGS) },
                onCourse = { navController.navigate(Routes.courseDetail(it)) },
                onAddCourse = { timetableId, dayOfWeek, startPeriod ->
                    navController.navigate(Routes.courseEditor(timetableId, dayOfWeek, startPeriod))
                }
            )
        }
        composable(
            Routes.LOGIN,
            arguments = listOf(
                navArgument("school") { type = NavType.StringType; defaultValue = "SCUT" },
                navArgument("returnTo") { type = NavType.StringType; defaultValue = "import" }
            )
        ) { entry ->
            val school = entry.arguments?.getString("school") ?: "SCUT"
            val returnTo = entry.arguments?.getString("returnTo") ?: "import"
            val onLoginSuccess: () -> Unit = {
                if (returnTo == "timetable") {
                    navController.popBackStack(Routes.TIMETABLE, false)
                } else {
                    navController.navigate(Routes.termImport("add", school)) { popUpTo(Routes.LOGIN) { inclusive = true } }
                }
            }
            if (school == "JNU") {
                JnuLoginScreen(
                    auth = container.jnuAuthRepository,
                    onAuthenticated = onLoginSuccess,
                    onBack = navController::navigateUp
                )
            } else {
                val vm: AuthViewModel = viewModel(factory = AuthViewModelFactory(login, container.academicTermsCache))
                AuthScreen(vm, onLoginSuccess, navController::navigateUp)
            }
        }
        composable(
            Routes.SCHOOL_PICKER,
            arguments = listOf(navArgument("mode") { type = NavType.StringType; defaultValue = "add" })
        ) { entry ->
            val mode = entry.arguments?.getString("mode") ?: "add"
            SchoolPickerScreen(
                onBack = navController::navigateUp,
                onSchoolSelected = { school ->
                    navController.navigate(Routes.termImport(mode, school))
                }
            )
        }

        composable(
            Routes.TERM_IMPORT,
            arguments = listOf(
                navArgument("mode") { type = NavType.StringType; defaultValue = "add" },
                navArgument("school") { type = NavType.StringType; defaultValue = "SCUT" }
            )
        ) { entry ->
            val mode = entry.arguments?.getString("mode") ?: "add"
            val school = entry.arguments?.getString("school") ?: "SCUT"
            val initialSchool = if (school == "JNU") com.example.awake.domain.model.SchoolCode.JNU
            else com.example.awake.domain.model.SchoolCode.SCUT
            val importMode = if (mode == "overwrite") com.example.awake.ui.importterm.ImportMode.OVERWRITE
            else com.example.awake.ui.importterm.ImportMode.ADD
            val vm: TermImportViewModel = viewModel(
                factory = TermImportViewModelFactory(
                    container.localRepository,
                    importer,
                    container.reminderCoordinator,
                    container.timetableSelectionStore,
                    container.scheduleRouter,
                    container.academicTermsCache,
                    container.authRepository,
                    container.jnuAuthRepository,
                    importMode,
                    container.jsonTimetableStore,
                    initialSchool
                )
            )
            TermImportScreen(
                vm,
                navController::navigateUp,
                onDone = { navController.popBackStack(Routes.TIMETABLE, false) },
                onLogin = { school -> navController.navigate(Routes.login(school)) },
                onSwitchSchool = { navController.popBackStack() }
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                local = container.localRepository,
                auth = container.authRepository,
                jnuAuth = container.jnuAuthRepository,
                reminderCoordinator = container.reminderCoordinator,
                selection = container.timetableSelectionStore,
                displaySettings = container.timetableDisplaySettingsStore,
                scheduleRouter = container.scheduleRouter,
                themeMode = container.themeModeFlow,
                onThemeModeChange = container::setThemeMode,
                onBack = { navController.popBackStack() },
                onLogin = { school -> navController.navigate(Routes.login(school, "timetable")) }
            )
        }
        composable(Routes.COURSE_DETAIL, arguments = listOf(navArgument("courseId") { type = NavType.LongType })) { entry ->
            val courseId = entry.arguments?.getLong("courseId") ?: return@composable
            val vm: CourseDetailViewModel = viewModel(factory = CourseDetailViewModelFactory(container.localRepository, container.reminderCoordinator, courseId))
            CourseDetailScreen(
                viewModel = vm,
                onBack = navController::navigateUp,
                onEditSection = { sectionId ->
                    navController.navigate(Routes.courseEditor(0L, 1, 1, sectionId = sectionId))
                },
                onAddSection = { timetableId, masterId ->
                    navController.navigate(Routes.courseEditor(timetableId, 1, 1, masterId = masterId))
                }
            )
        }
        composable(
            Routes.COURSE_EDITOR,
            arguments = listOf(
                navArgument("timetableId") { type = NavType.LongType },
                navArgument("dayOfWeek") { type = NavType.IntType },
                navArgument("startPeriod") { type = NavType.IntType },
                navArgument("sectionId") { type = NavType.LongType; defaultValue = -1L },
                navArgument("masterId") { type = NavType.LongType; defaultValue = -1L }
            )
        ) { entry ->
            // 编辑/详情添加模式下没有课表 id，从目标课程所属课表推断。
            val timetableIdFromRoute = entry.arguments?.getLong("timetableId") ?: -1L
            val dayOfWeek = entry.arguments?.getInt("dayOfWeek") ?: 1
            val startPeriod = entry.arguments?.getInt("startPeriod") ?: 1
            val sectionId = entry.arguments?.getLong("sectionId") ?: -1L
            val masterId = entry.arguments?.getLong("masterId") ?: -1L
            val vm: CourseEditorViewModel = viewModel(
                factory = CourseEditorViewModelFactory(
                    container.localRepository,
                    container.reminderCoordinator,
                    timetableIdFromRoute,
                    dayOfWeek,
                    startPeriod,
                    sectionId,
                    masterId
                )
            )
            CourseEditorScreen(
                viewModel = vm,
                onBack = navController::navigateUp,
                onDone = { navController.popBackStack(Routes.TIMETABLE, false) }
            )
        }
    }
}
