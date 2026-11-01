package com.checktodo.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputFilter;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Toast;
import android.widget.TextView;

import com.checktodo.app.ai.AiImageShell;
import com.checktodo.app.ai.AiPhotoProvider;
import com.checktodo.app.ai.AiPhotoStore;
import com.checktodo.app.ai.AiVerifySettings;
import com.checktodo.app.ai.GlmHttp;
import com.checktodo.app.data.TaskRepository;
import com.checktodo.app.data.TimetableRepository;
import com.checktodo.app.data.WeeklyReportStore;
import com.checktodo.app.data.WidgetRefresh;
import com.checktodo.app.domain.AiVerdict;
import com.checktodo.app.domain.AiVerifyConfig;
import com.checktodo.app.domain.AiVerifyRecord;
import com.checktodo.app.domain.AiVerifyConfig;
import com.checktodo.app.domain.ImageHash;
import com.checktodo.app.domain.PhotoChannel;
import com.checktodo.app.domain.ReminderLogic;
import com.checktodo.app.domain.Task;
import com.checktodo.app.domain.TaskLogic;
import com.checktodo.app.domain.TaskStatus;
import com.checktodo.app.domain.TaskStats;
import com.checktodo.app.domain.ThemeMode;
import com.checktodo.app.domain.Todo;
import com.checktodo.app.domain.TodoLogic;
import com.checktodo.app.domain.TodoStatus;
import com.checktodo.app.domain.VerificationPolicy;
import com.checktodo.app.domain.VerifyMode;
import com.checktodo.app.widget.CheckTodoWidget;
import com.checktodo.app.notification.ReminderScheduler;
import com.checktodo.app.timetable.TimetableController;

import android.provider.MediaStore;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class MainActivity extends Activity {
    private static final int REFRESH_MS = 30_000;
    private static final String PREFS_NAME = "check-todo";
    private static final String KEY_THEME_MODE = "theme_mode";
    private static final String KEY_NOTIFICATION_ASKED = "notification_permission_asked";
    private static final String KEY_BATTERY_PROMPT_DECLINED = "battery_optimization_prompt_declined";
    private static final int REQUEST_NOTIFICATIONS = 1001;
    /** 相机请求码：AI 打卡当前照。 */
    private static final int REQUEST_AI_CHECKIN_PHOTO = 1002;
    /** 相机请求码：AI 判定的标准照（编辑器内拍摄）。 */
    private static final int REQUEST_AI_REFERENCE_PHOTO = 1003;
    /** 相机请求码：待办 AI 判定的当前照。 */
    private static final int REQUEST_AI_TODO_CHECKIN_PHOTO = 1004;
    /** 相机请求码：待办 AI 判定的标准照（待办编辑器内拍摄）。 */
    private static final int REQUEST_AI_TODO_REFERENCE_PHOTO = 1005;
    /** 相册请求码：任务打卡当前照（ACTION_GET_CONTENT，结果先拷进私有目录再进既有链路）。 */
    private static final int REQUEST_AI_GALLERY_CHECKIN_PHOTO = 1006;
    /** 相册请求码：待办打卡当前照。 */
    private static final int REQUEST_AI_GALLERY_TODO_CHECKIN_PHOTO = 1007;
    /** 相册请求码：任务标准照（编辑器内选图）。 */
    private static final int REQUEST_AI_GALLERY_REFERENCE_PHOTO = 1008;
    /** 相册请求码：待办标准照（待办编辑器内选图）。 */
    private static final int REQUEST_AI_GALLERY_TODO_REFERENCE_PHOTO = 1009;
    private static final int MENU_TEST_NOTIFICATION = 10;
    /** 设置区菜单项：AI 判定设置（BYOK）。 */
    private static final int MENU_AI_SETTINGS = 11;
    /** 设置区菜单项：AI 周报（查看历史 + 手动生成本周）。 */
    private static final int MENU_AI_WEEKLY = 12;
    public static final String EXTRA_OPEN_TIMETABLE = "open_timetable";
    private static final String[] WEEKDAY_LABELS = {"一", "二", "三", "四", "五", "六", "日"};

    private enum Mode { TASKS, TODOS, TIMETABLE }

    private TaskRepository repository;
    private TimetableRepository timetableRepository;
    private TimetableController timetableController;
    private LinearLayout taskList;
    private TextView dateText;
    private Button newButton;
    private Button tasksButton;
    private Button todosButton;
    private Button timetableButton;
    private LinearLayout taskSubTabs;
    private Button todayButton;
    private Button allButton;
    private ImageButton themeButton;
    private ImageButton moreButton;
    private ThemeMode currentThemeMode = ThemeMode.FOLLOW_SYSTEM;
    private Mode mode = Mode.TODOS;
    private boolean taskTodayOnly = true;
    private boolean pendingExpanded = true;
    private boolean completedExpanded = false;
    private boolean receiverRegistered = false;
    private final Handler refreshHandler = new Handler(Looper.getMainLooper());

    /** 已渲染的条目视图缓存，用于局部刷新（只更新变化的单条目，不整页重建）。 */
    private final Map<String, View> taskItemViews = new HashMap<>();
    private final Map<String, View> todoItemViews = new HashMap<>();
    private View pendingHeader;
    private View completedHeader;
    private TextView pendingHeaderTitle;
    private TextView completedHeaderTitle;
    private String lastRenderDateKey;
    /** 月历单元格只在打开日历时创建一次，翻月与请假变更后复用同一批 TextView。 */
    private final List<TextView> calendarCells = new ArrayList<>();
    private LayoutInflater inflater;

    /** 等待相机返回的「打卡当前照」目标文件；null = 无进行中的拍照。 */
    private File pendingShotFile;
    /** 该拍照对应的任务 id（onActivityResult 据此续跑判定链路）。 */
    private String pendingShotTaskId;
    /** 该拍照对应的待办 id（onActivityResult 据此续跑判定链路）。 */
    private String pendingShotTodoId;
    /** 等待相机返回的「标准照」目标文件（任务编辑器内拍摄）。 */
    private File pendingReferenceFile;
    /** 标准照的归属编辑器：true = 待办编辑器，false = 任务编辑器。 */
    private boolean pendingReferenceForTodo;
    /**
     * 相册选图进行中：相册渠道不预建目标文件（Uri 是临时授权，结果回来才拷盘），
     * 用它挡住重复点击。渠道本身<b>不做状态</b>——它作为显式参数传给预检
     * （{@code runAiPrecheck(task, shot, channel)}），避免脏状态放宽口径。
     */
    private boolean pendingGalleryPick;
    /** 待办编辑器内已拍好的标准照路径；保存待办时写入。 */
    private String editorTodoReferencePhoto = "";
    /** 待办编辑器对话框内的标准照按钮（相机返回后回填文案）。 */
    private Button aiTodoReferenceButton;
    /** 编辑器内已拍好的标准照路径；保存任务时写入。 */
    private String editorReferencePhoto = "";
    /** 编辑器对话框内的标准照按钮与状态行（相机返回后回填文案）。 */
    private Button aiReferenceButton;
    /** 编辑器对话框内的「从相册选择」标准照按钮（N+12 相册渠道）。 */
    private Button aiReferenceGalleryButton;
    private TextView aiConfigState;
    /** AI 判定进行中的提示框（用于结果到达后关闭，避免重复上屏）。 */
    private AlertDialog aiProgressDialog;
    /** 判定结果是否已上屏（后台回调可能晚于 Activity 销毁，用它挡住重复处理）。 */
    private boolean aiResultConsumed;

    /** 卡片操作等外部数据变更后，App 界面立即同步刷新。 */
    private final BroadcastReceiver dataChangedReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            repository.reload();
            timetableRepository.reload();
            render();
        }
    };

    private final Runnable refreshRunnable = new Runnable() {
        @Override
        public void run() {
            refreshTick();
            refreshHandler.postDelayed(this, REFRESH_MS);
        }
    };

    /**
     * 在 Activity 基础 Context 上强制 uiMode 夜间位：亮色=NO、深色=YES、跟随系统=不改。
     * 这样显式深色也能命中 values-night 资源与 values-night/styles.xml 的深色主题父类，
     * 无需 AndroidX 的 AppCompatDelegate。
     */
    @Override
    protected void attachBaseContext(Context base) {
        currentThemeMode = ThemeMode.fromString(base
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_THEME_MODE, null));
        if (currentThemeMode == ThemeMode.FOLLOW_SYSTEM) {
            super.attachBaseContext(base);
            return;
        }
        Configuration config = new Configuration(base.getResources().getConfiguration());
        int nightBits = currentThemeMode == ThemeMode.DARK
                ? Configuration.UI_MODE_NIGHT_YES : Configuration.UI_MODE_NIGHT_NO;
        config.uiMode = (config.uiMode & ~Configuration.UI_MODE_NIGHT_MASK) | nightBits;
        super.attachBaseContext(base.createConfigurationContext(config));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        repository = new TaskRepository(this);
        timetableRepository = new TimetableRepository(this);
        timetableController = new TimetableController(this, timetableRepository);
        dateText = findViewById(R.id.date_text);
        taskList = findViewById(R.id.task_list);
        newButton = findViewById(R.id.button_new);
        tasksButton = findViewById(R.id.button_tab_tasks);
        todosButton = findViewById(R.id.button_tab_todos);
        timetableButton = findViewById(R.id.button_tab_timetable);
        taskSubTabs = findViewById(R.id.task_sub_tabs);
        todayButton = findViewById(R.id.button_today);
        allButton = findViewById(R.id.button_all);
        themeButton = findViewById(R.id.button_theme);
        moreButton = findViewById(R.id.button_more);
        inflater = LayoutInflater.from(this);

        newButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (mode == Mode.TODOS) {
                    showTodoEditor(null);
                } else if (mode == Mode.TIMETABLE) {
                    timetableController.showCourseEditor(null);
                } else {
                    showTaskEditor(null);
                }
            }
        });
        tasksButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                mode = Mode.TASKS;
                render();
            }
        });
        todosButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                mode = Mode.TODOS;
                render();
            }
        });
        timetableButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                mode = Mode.TIMETABLE;
                render();
            }
        });
        todayButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                taskTodayOnly = true;
                render();
            }
        });
        allButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                taskTodayOnly = false;
                render();
            }
        });
        themeButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                setThemeMode(currentThemeMode.next());
            }
        });
        moreButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                showMoreMenu(view);
            }
        });
        updateThemeIcon();
        if (getIntent() != null && getIntent().getBooleanExtra(EXTRA_OPEN_TIMETABLE, false)) {
            mode = Mode.TIMETABLE;
        }
        render();
        maybeShowCancelConfirm();
        maybeStartAiCheckInFromWidget();
        // 首次启动先解释用途，再触发系统权限弹窗；已授权则直接调度。
        if (savedInstanceState == null) {
            maybeShowNotificationPermissionDialog();
        }
        ReminderScheduler.rescheduleAll(this);
    }

    /**
     * API 33+ 通知权限流程：先解释，再弹系统权限；永久拒绝时引导到应用设置。
     * 已授权时不打扰。API 33 以下无需运行时权限。
     */
    private void maybeShowNotificationPermissionDialog() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
                || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                        == PackageManager.PERMISSION_GRANTED) {
            maybeShowBatteryOptimizationDialog();
            return;
        }

        final SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        boolean firstRequest = !prefs.getBoolean(KEY_NOTIFICATION_ASKED, false);
        boolean canAskAgain = firstRequest
                || shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS);
        if (!canAskAgain) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.notification_permission_blocked_title)
                    .setMessage(R.string.notification_permission_blocked_message)
                    .setNegativeButton(R.string.notification_permission_later,
                            new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface dialog, int which) {
                                    maybeShowBatteryOptimizationDialog();
                                }
                            })
                    .setPositiveButton(R.string.notification_permission_open_settings,
                            new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface dialog, int which) {
                                    Intent settings = new Intent(
                                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                            Uri.fromParts("package", getPackageName(), null));
                                    startActivity(settings);
                                }
                            })
                    .show();
            return;
        }

        new AlertDialog.Builder(this)
                .setTitle(R.string.notification_permission_title)
                .setMessage(R.string.notification_permission_message)
                .setNegativeButton(R.string.notification_permission_later,
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                maybeShowBatteryOptimizationDialog();
                            }
                        })
                .setPositiveButton(R.string.notification_permission_enable,
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                prefs.edit().putBoolean(KEY_NOTIFICATION_ASKED, true).apply();
                                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},
                                        REQUEST_NOTIFICATIONS);
                            }
                        })
                .show();
    }

    /**
     * 电池优化引导：未加入白名单且用户没有明确拒绝过时提示；
     * 拒绝后持久化偏好，后续启动不再骚扰。
     */
    private void maybeShowBatteryOptimizationDialog() {
        PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        if (powerManager == null || powerManager.isIgnoringBatteryOptimizations(getPackageName())) return;
        final SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        if (prefs.getBoolean(KEY_BATTERY_PROMPT_DECLINED, false)) return;

        new AlertDialog.Builder(this)
                .setTitle(R.string.battery_optimization_title)
                .setMessage(R.string.battery_optimization_message)
                .setNegativeButton(R.string.battery_optimization_later,
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                prefs.edit().putBoolean(KEY_BATTERY_PROMPT_DECLINED, true).apply();
                            }
                        })
                .setPositiveButton(R.string.battery_optimization_enable,
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                Intent intent = new Intent(
                                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                        Uri.fromParts("package", getPackageName(), null));
                                startActivity(intent);
                            }
                        })
                .show();
    }

    private void showMoreMenu(View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add(0, MENU_TEST_NOTIFICATION, 0, getString(R.string.menu_test_notification));
        // AI 判定设置（BYOK）：与主题设置同属设置区，放主界面「⋮」菜单。
        menu.getMenu().add(0, MENU_AI_SETTINGS, 1, "AI 判定设置");
        // AI 周报：查看历史周报 + 手动生成本周（周一自动生成上一周的）。
        menu.getMenu().add(0, MENU_AI_WEEKLY, 2, getString(R.string.menu_ai_weekly));
        menu.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(android.view.MenuItem menuItem) {
                if (menuItem.getItemId() == MENU_TEST_NOTIFICATION) {
                    sendTestNotification();
                    return true;
                }
                if (menuItem.getItemId() == MENU_AI_SETTINGS) {
                    showAiVerifySettings();
                    return true;
                }
                if (menuItem.getItemId() == MENU_AI_WEEKLY) {
                    showWeeklyReports();
                    return true;
                }
                return false;
            }
        });
        menu.show();
    }

    private void sendTestNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, R.string.notification_test_missing_permission, Toast.LENGTH_SHORT).show();
            maybeShowNotificationPermissionDialog();
            return;
        }
        ReminderScheduler.scheduleTestNotification(this);
        Toast.makeText(this, R.string.notification_test_scheduled, Toast.LENGTH_SHORT).show();
    }

    private void setThemeMode(ThemeMode mode) {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putString(KEY_THEME_MODE, mode.value()).apply();
        Toast.makeText(this, getString(R.string.theme_prefix) + themeLabel(mode), Toast.LENGTH_SHORT).show();
        recreate();
    }

    private void updateThemeIcon() {
        int icon;
        if (currentThemeMode == ThemeMode.LIGHT) {
            icon = R.drawable.ic_theme_light;
        } else if (currentThemeMode == ThemeMode.DARK) {
            icon = R.drawable.ic_theme_dark;
        } else {
            icon = R.drawable.ic_theme_auto;
        }
        themeButton.setImageResource(icon);
        themeButton.setContentDescription(getString(R.string.theme_switch) + "：" + themeLabel(currentThemeMode));
    }

    private String themeLabel(ThemeMode mode) {
        if (mode == ThemeMode.LIGHT) return getString(R.string.theme_mode_light);
        if (mode == ThemeMode.DARK) return getString(R.string.theme_mode_dark);
        return getString(R.string.theme_mode_follow_system);
    }

    @Override
    protected void onResume() {
        super.onResume();
        repository.reload();
        timetableRepository.reload();
        // 从系统设置返回、安装后首次进入、或进程重启后都补一次全量调度。
        ReminderScheduler.rescheduleAll(this);
        registerDataReceiver();
        refreshHandler.post(refreshRunnable);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_NOTIFICATIONS) return;
        if (grantResults != null && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, R.string.notification_permission_granted_toast, Toast.LENGTH_SHORT).show();
            ReminderScheduler.rescheduleAll(this);
            maybeShowBatteryOptimizationDialog();
        } else {
            Toast.makeText(this, R.string.notification_permission_denied_toast, Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent != null && intent.getBooleanExtra(EXTRA_OPEN_TIMETABLE, false)) {
            mode = Mode.TIMETABLE;
            render();
        }
        maybeShowCancelConfirm();
        maybeStartAiCheckInFromWidget();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_AI_CHECKIN_PHOTO) {
            handleAiCheckInPhotoResult(resultCode);
            return;
        }
        if (requestCode == REQUEST_AI_TODO_CHECKIN_PHOTO) {
            handleAiTodoCheckInPhotoResult(resultCode);
            return;
        }
        if (requestCode == REQUEST_AI_REFERENCE_PHOTO) {
            if (pendingReferenceForTodo) {
                handleAiTodoReferencePhotoResult(resultCode);
            } else {
                handleAiReferencePhotoResult(resultCode);
            }
            return;
        }
        if (requestCode == REQUEST_AI_TODO_REFERENCE_PHOTO) {
            handleAiTodoReferencePhotoResult(resultCode);
            return;
        }
        // 相册渠道（N+12）：Uri 只是临时授权，必须先流式拷进私有目录，
        // 之后与拍照图走完全相同的链路（采样 → aHash → data URI → GLM）。
        if (requestCode == REQUEST_AI_GALLERY_CHECKIN_PHOTO) {
            handleAiGalleryCheckInResult(resultCode, data, false);
            return;
        }
        if (requestCode == REQUEST_AI_GALLERY_TODO_CHECKIN_PHOTO) {
            handleAiGalleryCheckInResult(resultCode, data, true);
            return;
        }
        if (requestCode == REQUEST_AI_GALLERY_REFERENCE_PHOTO) {
            handleAiGalleryReferenceResult(resultCode, data, false);
            return;
        }
        if (requestCode == REQUEST_AI_GALLERY_TODO_REFERENCE_PHOTO) {
            handleAiGalleryReferenceResult(resultCode, data, true);
            return;
        }
        timetableController.handleCsvResult(requestCode, resultCode, data);
        timetableController.handleHtmlResult(requestCode, resultCode, data);
    }

    /**
     * 相册选中的<b>打卡当前照</b>返回：取消/失败即结束（不写任何记录）；
     * 成功则先拷进私有目录，再跑 L1 预检（相册渠道：跳过新鲜度、保留去重）→ L2 判定。
     *
     * @param forTodo true = 待办完成流程，false = 任务打卡流程
     */
    private void handleAiGalleryCheckInResult(int resultCode, Intent data, boolean forTodo) {
        pendingGalleryPick = false;
        final String taskId = pendingShotTaskId;
        final String todoId = pendingShotTodoId;
        pendingShotTaskId = null;
        pendingShotTodoId = null;
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            Toast.makeText(this, forTodo ? "已取消选择图片，待办未完成" : "已取消选择图片，未记录打卡",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        File shot = AiPhotoStore.copyFromUri(this, data.getData(),
                PhotoChannel.GALLERY.filePrefix("shot"));
        if (shot == null || !shot.exists() || shot.length() == 0) {
            deleteQuietly(shot);
            Toast.makeText(this, "读取相册图片失败，请换一张重试", Toast.LENGTH_LONG).show();
            return;
        }
        if (forTodo) {
            final Todo todo = todoId == null ? null : findTodo(todoId);
            if (todo == null) {
                deleteQuietly(shot);
                return;
            }
            runAiTodoPrecheck(todo, shot, PhotoChannel.GALLERY);
            return;
        }
        final Task task = taskId == null ? null : findTask(taskId);
        if (task == null) {
            deleteQuietly(shot);
            return;
        }
        runAiPrecheck(task, shot, PhotoChannel.GALLERY);
    }

    /**
     * 相册选中的<b>标准照</b>返回：拷进私有目录后回填编辑器状态，与拍照路径完全一致
     * （保存时随任务/待办持久化）。选图失败不清空已有标准照。
     */
    private void handleAiGalleryReferenceResult(int resultCode, Intent data, boolean forTodo) {
        pendingGalleryPick = false;
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        File reference = AiPhotoStore.copyFromUri(this, data.getData(),
                PhotoChannel.GALLERY.filePrefix("ref"));
        if (reference == null || !reference.exists() || reference.length() == 0) {
            deleteQuietly(reference);
            Toast.makeText(this, "读取相册图片失败，请换一张重试", Toast.LENGTH_LONG).show();
            return;
        }
        if (forTodo) {
            applyTodoReferencePhoto(reference);
        } else {
            applyTaskReferencePhoto(reference);
        }
    }

    /** 任务标准照落地：路径 + 按钮文案 + 状态行（拍照与相册两条渠道共用）。 */
    private void applyTaskReferencePhoto(File reference) {
        editorReferencePhoto = reference.getAbsolutePath();
        if (aiReferenceButton != null) {
            aiReferenceButton.setText("重拍标准照（已拍摄）");
        }
        if (aiConfigState != null) {
            aiConfigState.setText("标准照：" + reference.getName());
        }
    }

    /** 待办标准照落地：路径 + 按钮文案（拍照与相册两条渠道共用）。 */
    private void applyTodoReferencePhoto(File reference) {
        editorTodoReferencePhoto = reference.getAbsolutePath();
        if (aiTodoReferenceButton != null) {
            aiTodoReferenceButton.setText("重拍标准照（已拍摄）");
        }
    }

    /**
     * 打卡当前照返回：取消/失败即结束（不写任何记录，用户可再点一次圆区重拍）；
     * 成功则跑 L1 预检 → L2 判定。
     */
    private void handleAiCheckInPhotoResult(int resultCode) {
        final File shot = pendingShotFile;
        final String taskId = pendingShotTaskId;
        pendingShotFile = null;
        pendingShotTaskId = null;
        if (shot == null || taskId == null) return;
        if (resultCode != RESULT_OK || !shot.exists() || shot.length() == 0) {
            deleteQuietly(shot);
            Toast.makeText(this, "已取消拍照，未记录打卡", Toast.LENGTH_SHORT).show();
            return;
        }
        final Task task = findTask(taskId);
        if (task == null) {
            deleteQuietly(shot);
            return;
        }
        // 相机返回 = 拍照渠道（新鲜度 + 去重都执行）。
        runAiPrecheck(task, shot, PhotoChannel.CAMERA);
    }

    /**
     * 标准照返回：路径回填编辑器状态，保存任务时随任务持久化。
     * 拍失败不清空已有标准照。
     */
    private void handleAiReferencePhotoResult(int resultCode) {
        final File reference = pendingReferenceFile;
        pendingReferenceFile = null;
        if (reference == null) return;
        if (resultCode != RESULT_OK || !reference.exists() || reference.length() == 0) {
            deleteQuietly(reference);
            return;
        }
        applyTaskReferencePhoto(reference);
    }

    /** 待办标准照返回：路径回填待办编辑器状态，保存待办时随待办持久化。 */
    private void handleAiTodoReferencePhotoResult(int resultCode) {
        final File reference = pendingReferenceFile;
        pendingReferenceFile = null;
        if (reference == null) return;
        if (resultCode != RESULT_OK || !reference.exists() || reference.length() == 0) {
            deleteQuietly(reference);
            return;
        }
        applyTodoReferencePhoto(reference);
    }

    @Override
    protected void onPause() {
        super.onPause();
        refreshHandler.removeCallbacks(refreshRunnable);
        unregisterDataReceiver();
        // 离开前台时收起判定提示框，避免窗口句柄失效后再 dismiss 抛异常。
        dismissAiProgress();
    }

    private void registerDataReceiver() {
        if (receiverRegistered) return;
        IntentFilter filter = new IntentFilter(WidgetRefresh.ACTION_REFRESH);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // API 33+ 必须显式声明导出行为，否则注册会抛异常。
                registerReceiver(dataChangedReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(dataChangedReceiver, filter);
            }
            receiverRegistered = true;
        } catch (Exception ignored) {
            receiverRegistered = false;
        }
    }

    private void unregisterDataReceiver() {
        if (!receiverRegistered) return;
        try {
            unregisterReceiver(dataChangedReceiver);
        } catch (Exception ignored) {
            // Activity 已销毁时忽略。
        }
        receiverRegistered = false;
    }

    private void render() {
        Date now = new Date();
        dateText.setText(new SimpleDateFormat("M月d日 EEEE", Locale.CHINA).format(now));
        updateTabs();
        taskList.removeAllViews();
        taskItemViews.clear();
        todoItemViews.clear();
        pendingHeader = null;
        completedHeader = null;
        pendingHeaderTitle = null;
        completedHeaderTitle = null;
        lastRenderDateKey = TaskLogic.dateKey(now);
        if (mode == Mode.TODOS) {
            renderTodos();
        } else if (mode == Mode.TIMETABLE) {
            taskList.removeAllViews();
            timetableController.show(taskList);
        } else {
            renderTasks(now);
        }
    }

    /**
     * 30 秒周期刷新只更新状态类文本（状态标签、连续天数、勾选外观），
     * 不重建列表视图；仅当跨天（日期变化）时才整页重渲染。
     */
    private void refreshTick() {
        String todayKey = TaskLogic.dateKey(new Date());
        if (!todayKey.equals(lastRenderDateKey)) {
            render();
            return;
        }
        Date now = new Date();
        if (mode == Mode.TODOS) {
            for (Map.Entry<String, View> entry : todoItemViews.entrySet()) {
                Todo todo = findTodo(entry.getKey());
                if (todo != null) bindTodoView(entry.getValue(), todo);
            }
        } else if (mode == Mode.TASKS) {
            for (Map.Entry<String, View> entry : taskItemViews.entrySet()) {
                Task task = findTask(entry.getKey());
                if (task != null) bindTaskView(entry.getValue(), task, now);
            }
        } else {
            timetableController.refresh();
        }
    }

    private void updateTabs() {
        newButton.setText(mode == Mode.TODOS ? "新建待办"
                : mode == Mode.TIMETABLE ? "新建课程" : "新建任务");
        taskSubTabs.setVisibility(mode == Mode.TASKS ? View.VISIBLE : View.GONE);
        tasksButton.setSelected(mode == Mode.TASKS);
        todosButton.setSelected(mode == Mode.TODOS);
        timetableButton.setSelected(mode == Mode.TIMETABLE);
        tasksButton.setTextColor(getColor(mode == Mode.TASKS
                ? R.color.tabTextSelected : R.color.tabTextNormal));
        todosButton.setTextColor(getColor(mode == Mode.TODOS
                ? R.color.tabTextSelected : R.color.tabTextNormal));
        timetableButton.setTextColor(getColor(mode == Mode.TIMETABLE
                ? R.color.tabTextSelected : R.color.tabTextNormal));
        todayButton.setSelected(taskTodayOnly);
        allButton.setSelected(!taskTodayOnly);
        todayButton.setTextColor(getColor(taskTodayOnly
                ? R.color.tabTextSelected : R.color.tabTextNormal));
        allButton.setTextColor(getColor(!taskTodayOnly
                ? R.color.tabTextSelected : R.color.tabTextNormal));
    }

    private void renderTasks(Date now) {
        List<Task> visible = visibleTasks(now);

        if (visible.isEmpty()) {
            View empty = LayoutInflater.from(this).inflate(R.layout.empty_task, taskList, false);
            ((TextView) empty.findViewById(R.id.empty_text)).setText(taskTodayOnly
                    ? "今天没有需要打卡的任务" : "还没有任务，先创建一个吧");
            taskList.addView(empty);
            return;
        }

        LayoutInflater inflater = LayoutInflater.from(this);
        for (final Task task : visible) {
            View item = inflater.inflate(R.layout.item_task, taskList, false);
            taskItemViews.put(task.id, item);
            bindTaskView(item, task, now);
            taskList.addView(item);
        }
    }

    /**
     * 当前视图的可见任务列表，统一排序：可打卡（endTime 升序）→ 已完成 → 已错过 → 未开始 → 请假
     * → 非今日重复日（endTime 升序）。今日视图只含今天需要打卡的任务，全部视图含全部任务。
     */
    private List<Task> visibleTasks(Date now) {
        List<Task> visible = new ArrayList<>();
        for (Task task : repository.getTasks()) {
            if (!taskTodayOnly || TaskLogic.isScheduled(task, now)) visible.add(task);
        }
        return TaskLogic.sortForToday(visible, completedDatesByTask(), now);
    }

    private Map<String, Set<String>> completedDatesByTask() {
        Map<String, Set<String>> result = new HashMap<>();
        for (Task task : repository.getTasks()) {
            result.put(task.id, repository.getCompletedDates(task.id));
        }
        return result;
    }

    /** 打卡/取消打卡后按新排序就地移动卡片，避免整页重建造成闪屏。 */
    private void refreshTaskItemPosition(String taskId) {
        View item = taskItemViews.get(taskId);
        if (item == null || mode != Mode.TASKS) return;
        List<Task> ordered = visibleTasks(new Date());
        int target = -1;
        for (int i = 0; i < ordered.size(); i++) {
            if (ordered.get(i).id.equals(taskId)) {
                target = i;
                break;
            }
        }
        if (target < 0) return;
        int current = taskList.indexOfChild(item);
        if (current >= 0 && current != target) {
            taskList.removeView(item);
            taskList.addView(item, target);
        }
    }

    /** 绑定/刷新单个任务卡片的全部展示内容，渲染与局部刷新共用同一条路径。 */
    private void bindTaskView(final View item, final Task task, Date now) {
        TextView title = item.findViewById(R.id.task_title);
        TextView status = item.findViewById(R.id.task_status);
        TextView range = item.findViewById(R.id.task_range);
        TextView weekdays = item.findViewById(R.id.task_weekdays);
        TextView stats = item.findViewById(R.id.task_stats);
        TextView streak = item.findViewById(R.id.task_streak);
        final TextView check = item.findViewById(R.id.task_check);
        View overflow = item.findViewById(R.id.task_overflow);
        ProgressBar progress = item.findViewById(R.id.task_progress);
        final android.view.View.OnClickListener openCalendar = new android.view.View.OnClickListener() {
            @Override
            public void onClick(View view) {
                showTaskCalendar(task);
            }
        };
        Set<String> completedDates = repository.getCompletedDates(task.id);

        title.setText(aiTitleLabel(task));
        range.setText(task.startTime + " - " + task.endTime);
        weekdays.setText(weekdayAbbrev(task));
        streak.setText("连续 " + TaskLogic.calculateStreak(task, completedDates, now) + " 天");

        // 统计：总天数 = 创建日至今的当前重复日；0 总天数时显示 0/0 与 --。
        TaskStats taskStats = TaskStats.compute(task, completedDates, now);
        // 今日打卡带「待复核」标记（AI uncertain / 降级 / 申诉）时在统计行显式提示。
        String reviewTag = repository.hasReviewToday(task.id, now) ? " 待复核" : "";
        if (taskStats.totalDays == 0) {
            stats.setText("0/0 天 --" + reviewTag);
            progress.setVisibility(View.GONE);
            progress.setProgress(0);
        } else {
            stats.setText(taskStats.completedDays + "/" + taskStats.totalDays + " 天 "
                    + taskStats.ratePercent + "%" + reviewTag);
            progress.setVisibility(View.VISIBLE);
            progress.setProgress(taskStats.ratePercent);
        }

        TaskStatus taskStatus = TaskLogic.taskStatus(task, completedDates, now);
        status.setText(statusLabel(taskStatus));
        status.setBackgroundResource(statusBackground(taskStatus));
        status.setTextColor(statusTextColor(taskStatus));

        // 点击标题或连续天数 → 打开该任务的打卡日历。
        title.setOnClickListener(openCalendar);
        streak.setOnClickListener(openCalendar);

        // 左侧圆形勾选区：今日视图可打卡时为强调色圆环，点击即打卡；
        // 已完成为实心对勾，其他状态灰显且不可点。全部视图仅展示、不可打卡。
        if (mode == Mode.TASKS && taskTodayOnly) {
            overflow.setVisibility(View.GONE);
            if (taskStatus == TaskStatus.ACTIVE) {
                check.setText("");
                check.setBackgroundResource(R.drawable.task_check_active);
                check.setAlpha(1f);
                check.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View view) {
                        // AI 拍照判定任务：打卡入口改走「选来源（拍照/相册）→ L1 预检 → L2 判定」链路。
                        if (task.isAiPhoto()) {
                            pickAiCheckInSource(task, null, false);
                            return;
                        }
                        // 打卡成功后只局部刷新该卡片，避免整页 removeAllViews 重建。
                        if (repository.checkIn(task.id, new Date())) {
                            updateTaskItem(task.id);
                            refreshTaskItemPosition(task.id);
                        }
                    }
                });
            } else if (taskStatus == TaskStatus.DONE) {
                check.setText("✓");
                check.setBackgroundResource(R.drawable.todo_checkbox_done);
                check.setAlpha(1f);
                // 已完成状态下点击圆区 → 确认取消今日打卡（仅限今天，防误点）。
                check.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View view) {
                        confirmCancelCheckIn(task);
                    }
                });
            } else {
                check.setText("");
                check.setBackgroundResource(R.drawable.todo_checkbox_empty);
                check.setAlpha(0.35f);
                check.setOnClickListener(null);
            }
        } else {
            if (taskStatus == TaskStatus.DONE) {
                check.setText("✓");
                check.setBackgroundResource(R.drawable.todo_checkbox_done);
                check.setAlpha(1f);
            } else {
                check.setText("");
                check.setBackgroundResource(R.drawable.todo_checkbox_empty);
                check.setAlpha(0.35f);
            }
            check.setOnClickListener(null);
            overflow.setVisibility(View.VISIBLE);
            overflow.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    PopupMenu menu = new PopupMenu(MainActivity.this, view);
                    menu.getMenu().add(0, 1, 0, "编辑");
                    menu.getMenu().add(0, 2, 1, "删除");
                    if (task.isAiPhoto()) {
                        menu.getMenu().add(0, 3, 2, "AI 判定记录");
                        menu.getMenu().add(0, 4, 3, "AI 判定设置");
                    }
                    menu.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
                        @Override
                        public boolean onMenuItemClick(android.view.MenuItem menuItem) {
                            if (menuItem.getItemId() == 1) {
                                showTaskEditor(task);
                            } else if (menuItem.getItemId() == 2) {
                                confirmDeleteTask(task);
                            } else if (menuItem.getItemId() == 3) {
                                showAiVerdictHistory(task);
                            } else if (menuItem.getItemId() == 4) {
                                showAiVerifySettings();
                            }
                            return true;
                        }
                    });
                    menu.show();
                }
            });
            item.setOnClickListener(openCalendar);
        }
    }

    /** 重复日缩写：如「一三五」「一二三四五六日」。 */
    private String weekdayAbbrev(Task task) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < 7; i++) {
            if (task.weekdays[i]) builder.append(WEEKDAY_LABELS[i]);
        }
        return builder.toString();
    }

    /** 取消今日打卡的确认链路：App 内点击圆区与小卡片广播入口共用同一对话框。 */
    private void confirmCancelCheckIn(final Task task) {
        new AlertDialog.Builder(this)
                .setTitle("取消今日打卡")
                .setMessage("取消「" + task.name + "」今天的打卡记录？统计与连续天数会同步更新。")
                .setNegativeButton("保留", null)
                .setPositiveButton("取消打卡", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        if (repository.cancelCheckIn(task.id, new Date())) {
                            updateTaskItem(task.id);
                            refreshTaskItemPosition(task.id);
                        }
                    }
                })
                .show();
    }

    /** 小卡片点击已打卡条目时携带该 extra 启动 App，进入同一条取消确认链路。 */
    private void maybeShowCancelConfirm() {
        Intent intent = getIntent();
        if (intent == null) return;
        final String taskId = intent.getStringExtra(CheckTodoWidget.EXTRA_CANCEL_TASK_ID);
        if (taskId == null || taskId.isEmpty()) return;
        intent.removeExtra(CheckTodoWidget.EXTRA_CANCEL_TASK_ID);
        Task task = findTask(taskId);
        if (task == null) return;
        confirmCancelCheckIn(task);
    }

    /**
     * 小卡片点击 AI 拍照任务的可打卡条目：携带 {@link CheckTodoWidget#EXTRA_AI_CHECKIN_TASK_ID}
     * 启动 App，直接进入与 App 内点击圆区完全相同的拍照判定链路。
     */
    private void maybeStartAiCheckInFromWidget() {
        Intent intent = getIntent();
        if (intent == null) return;
        final String taskId = intent.getStringExtra(CheckTodoWidget.EXTRA_AI_CHECKIN_TASK_ID);
        if (taskId == null || taskId.isEmpty()) return;
        intent.removeExtra(CheckTodoWidget.EXTRA_AI_CHECKIN_TASK_ID);
        Task task = findTask(taskId);
        if (task == null) return;
        // 卡片侧已判定为「可打卡」；这里再校验一次，跨天/进程重启后状态可能已变。
        if (!TaskLogic.canCheckIn(task, repository.getCompletedDates(task.id), new Date())) {
            Toast.makeText(this, "「" + task.name + "」当前不在可打卡时段", Toast.LENGTH_SHORT).show();
            return;
        }
        // 卡片入口与 App 内点击圆区完全同一交互：先选来源（拍照 / 从相册选择）。
        pickAiCheckInSource(task, null, false);
    }

    // ------------------------------------------------------------------
    // AI 拍照判定打卡（相机 → L1 离线预检 → L2 模型判定 → 结果映射）
    // ------------------------------------------------------------------

    /**
     * 启动 AI 判定打卡：用系统相机拍当前照（{@code ACTION_IMAGE_CAPTURE} +
     * {@code EXTRA_OUTPUT} 指向 app 私有目录的 content URI，不引入 CameraX）。
     *
     * <p>未拍标准照时无法做视觉对比，但仍不阻塞：直接进入拍照，判定阶段按
     * 「配置不完整」走降级路径（完成打卡 + 待复核）。</p>
     */
    private void startAiCheckIn(final Task task) {
        if (pendingShotFile != null) {
            Toast.makeText(this, "上一次拍照还在进行中", Toast.LENGTH_SHORT).show();
            return;
        }
        File shot = AiPhotoStore.newShotFile(this);
        Intent capture = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        Uri output = AiPhotoProvider.uriFor(this, shot);
        capture.putExtra(MediaStore.EXTRA_OUTPUT, output);
        // 相机进程需要写回该 URI：显式授予读写权限（Provider 本身 exported=false）。
        capture.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            pendingShotTaskId = task.id;
            pendingShotFile = shot;
            startActivityForResult(capture, REQUEST_AI_CHECKIN_PHOTO);
        } catch (Exception exception) {
            pendingShotFile = null;
            pendingShotTaskId = null;
            Toast.makeText(this, "无法启动系统相机：" + exception.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    /**
     * 打卡照的<b>渠道选择</b>：先弹「拍照 / 从相册选择 / 取消」，再按所选渠道进入
     * 对应流程（拍照走相机，相册走 {@code ACTION_GET_CONTENT}）。
     *
     * <p>两条渠道之后完全同链：拷贝落盘 → L1 预检（渠道口径不同）→ L2 GLM 判定
     * （含三模型失败转移）→ 结果映射，降级铁律一致。</p>
     *
     * @param forTodo true = 待办完成流程，false = 任务打卡流程
     */
    private void pickAiCheckInSource(final Task task, final Todo todo, final boolean forTodo) {
        if (pendingShotFile != null || pendingGalleryPick) {
            Toast.makeText(this, "上一次拍照还在进行中", Toast.LENGTH_SHORT).show();
            return;
        }
        String[] items = {PhotoChannel.CAMERA.label(), PhotoChannel.GALLERY.label()};
        new AlertDialog.Builder(this)
                .setTitle(PhotoChannel.PICKER_TITLE)
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        if (which == 1) {
                            startAiGalleryCheckIn(task, todo, forTodo);
                        } else if (forTodo) {
                            startAiTodoCheckIn(todo);
                        } else {
                            startAiCheckIn(task);
                        }
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /**
     * 相册渠道：{@code ACTION_GET_CONTENT}（image/*）选一张图。
     *
     * <p>不预建目标文件——相册 Uri 是临时授权，必须等结果回来后由
     * {@link AiPhotoStore#copyFromUri} 流式拷进私有目录（见 onActivityResult 路由）。
     * 选图过程中用 {@link #pendingGalleryPick} 挡住重复点击，任务/待办 id 先存进
     * pending 字段，结果回来据此续跑判定链路。</p>
     */
    private void startAiGalleryCheckIn(Task task, Todo todo, boolean forTodo) {
        if (pendingShotFile != null || pendingGalleryPick) {
            Toast.makeText(this, "上一次选图还在进行中", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent pick = new Intent(Intent.ACTION_GET_CONTENT);
        pick.setType("image/*");
        pick.addCategory(Intent.CATEGORY_OPENABLE);
        try {
            pendingGalleryPick = true;
            if (forTodo) {
                pendingShotTodoId = todo == null ? null : todo.id;
                pendingShotTaskId = null;
                startActivityForResult(pick, REQUEST_AI_GALLERY_TODO_CHECKIN_PHOTO);
            } else {
                pendingShotTaskId = task == null ? null : task.id;
                pendingShotTodoId = null;
                startActivityForResult(pick, REQUEST_AI_GALLERY_CHECKIN_PHOTO);
            }
        } catch (Exception exception) {
            pendingGalleryPick = false;
            pendingShotTaskId = null;
            pendingShotTodoId = null;
            Toast.makeText(this, "无法打开相册：" + exception.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    /** 标准照的相册渠道：编辑器内选图，结果回填编辑器状态（与拍照一致）。 */
    private void startAiGalleryReference(boolean forTodo) {
        if (pendingReferenceFile != null || pendingGalleryPick) {
            Toast.makeText(this, "上一次选图还在进行中", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent pick = new Intent(Intent.ACTION_GET_CONTENT);
        pick.setType("image/*");
        pick.addCategory(Intent.CATEGORY_OPENABLE);
        try {
            pendingGalleryPick = true;
            pendingReferenceForTodo = forTodo;
            startActivityForResult(pick, forTodo
                    ? REQUEST_AI_GALLERY_TODO_REFERENCE_PHOTO
                    : REQUEST_AI_GALLERY_REFERENCE_PHOTO);
        } catch (Exception exception) {
            pendingGalleryPick = false;
            Toast.makeText(this, "无法打开相册：" + exception.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    /**
     * L1 离线预检（<b>渠道显式版</b>）：EXIF 新鲜度 + aHash 与该任务历史打卡照片去重。
     * 两项都是域层纯函数；Bitmap→像素矩阵的薄壳转换在 {@link AiImageShell}。
     *
     * <p><b>渠道口径</b>：拍照渠道两项都执行；相册渠道<b>跳过新鲜度</b>（相册图本来就是
     * 历史图，按新鲜期拦会把正常打卡堵死）、<b>保留去重</b>（防重复交同一张图）。
     * 渠道必须显式传参，不靠「shotAtMillis 是否为 null」隐式推断。</p>
     *
     * <p>预检拦截（旧图 / 重复图）→ 展示理由，可换渠道重来，不写完成记录。</p>
     */
    private void runAiPrecheck(final Task task, final File shot, final PhotoChannel channel) {
        Date now = new Date();
        String dateKey = TaskLogic.dateKey(now);
        Long shotAt = AiImageShell.shotAtMillisOrNull(shot);
        String hashHex = AiImageShell.hashHexOrNull(shot);
        // 历史样本只取「已完成打卡」的照片哈希（域层口径），fail 的照片不作为重复基准。
        List<String> history = VerificationPolicy.dedupHashes(repository.getAiRecords(task.id));
        Long currentHash = hashHex == null ? null : ImageHash.fromHex(hashHex);
        VerificationPolicy.Precheck precheck = VerificationPolicy.precheck(channel, shotAt,
                now.getTime(), 0, currentHash, history);
        if (!precheck.passed) {
            showAiPrecheckBlocked(task, precheck, shot);
            return;
        }
        callAiVerdict(task, shot, hashHex == null ? "" : hashHex, dateKey, now);
    }

    /**
     * L2：组装请求并异步调 GLM。
     *
     * <p><b>降级铁律</b>：未配 key / 无标准照 / 网络失败 / 超时 / 额度耗尽 / 响应不可
     * 解析，一律 {@code VerificationPolicy.degraded(...)}——完成打卡 + 待复核，
     * 打卡流程永不阻塞。</p>
     */
    private void callAiVerdict(final Task task, final File shot, final String hashHex,
                               final String dateKey, final Date now) {
        final AiVerifyConfig config = AiVerifySettings.load(this);
        if (!task.hasReferencePhoto()) {
            applyVerdict(task, shot, hashHex, dateKey, now,
                    VerificationPolicy.degraded("该任务尚未拍摄标准照"), null);
            return;
        }
        if (!VerificationPolicy.shouldCallModel(config.apiKey)) {
            applyVerdict(task, shot, hashHex, dateKey, now,
                    VerificationPolicy.degraded("未配置 API key"), null);
            return;
        }
        File reference = new File(task.aiReferencePhoto);
        final String referenceUri = AiImageShell.dataUriOrNull(reference);
        final String currentUri = AiImageShell.dataUriOrNull(shot);
        if (referenceUri == null || currentUri == null) {
            applyVerdict(task, shot, hashHex, dateKey, now,
                    VerificationPolicy.degraded("照片读取失败"), null);
            return;
        }
        // 特征描述作为【证据】交给域层做定界符包裹与消毒（提示词注入防护）。
        // 请求体在执行器线程内按判定链逐模型组装（N+11：失败转移见 ModelChain）。
        aiResultConsumed = false;
        showAiProgress(task);
        GlmHttp.requestAsync(config, task.name, task.aiFeature, referenceUri, currentUri,
                new GlmHttp.Callback() {
            @Override
            public void onResult(final AiVerdict verdict, final String failureWhy) {
                // 后台线程 → UI 线程：所有上屏与写库都在主线程做。
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        dismissAiProgress();
                        if (aiResultConsumed || isFinishing()) return;
                        aiResultConsumed = true;
                        VerificationPolicy.Outcome outcome = verdict == null
                                ? VerificationPolicy.degraded(failureWhy)
                                : VerificationPolicy.decideOrNull(verdict);
                        applyVerdict(task, shot, hashHex, dateKey, now, outcome, verdict);
                    }
                });
            }
        });
    }

    /**
     * 判定结果落地：写判定历史 + 按 {@code outcome.complete} 决定是否完成打卡，然后展示。
     * fail 时额外给「申诉并完成」出口（用户坚持 → 人工覆盖，记录 appealed）。
     */
    private void applyVerdict(Task task, File shot, String hashHex, String dateKey, Date now,
                              VerificationPolicy.Outcome outcome, AiVerdict verdict) {
        AiVerifyRecord record = new AiVerifyRecord(dateKey, now.getTime(),
                outcome.verdictKey, outcome.confidence,
                outcome.reason, outcome.degraded, false, outcome.complete, hashHex);
        repository.checkInWithVerdict(task.id, record, outcome.complete);
        repository.reload();
        if (outcome.complete) {
            updateTaskItem(task.id);
            refreshTaskItemPosition(task.id);
            showAiOutcomeDialog(task, outcome, verdict, false);
        } else {
            showAiOutcomeDialog(task, outcome, verdict, true);
        }
        deleteQuietly(shot);
    }

    /**
     * 判定结果对话框：pass = 简短提示；uncertain / 降级 = 说明「已完成 + 待复核」；
     * fail = 展示理由并提供「重新拍摄」与「申诉并完成」两个出口。
     */
    private void showAiOutcomeDialog(final Task task, VerificationPolicy.Outcome outcome,
                                     AiVerdict verdict, boolean failed) {
        String title;
        if (failed) {
            title = "AI 判定未通过";
        } else if (outcome.needsReview) {
            title = "已完成打卡（待复核）";
        } else {
            title = "AI 判定通过";
        }
        StringBuilder message = new StringBuilder();
        message.append(outcome.reason.isEmpty() ? "打卡已记录。" : outcome.reason);
        if (verdict != null) {
            message.append("\n\n置信度：").append(verdict.confidence).append("%");
        }
        if (outcome.degraded) {
            message.append("\n\n本次未获得模型判定，已按本地预检（拍摄时间新鲜度 + 照片去重）完成打卡，")
                    .append("可在「AI 判定设置」中检查 base_url / model / API key。");
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message.toString());
        if (failed) {
            builder.setNegativeButton("申诉并完成", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    if (repository.appealLastVerdict(task.id, TaskLogic.dateKey(new Date()))) {
                        repository.reload();
                        updateTaskItem(task.id);
                        refreshTaskItemPosition(task.id);
                        Toast.makeText(MainActivity.this, "已按申诉完成打卡，记录标记为待复核",
                                Toast.LENGTH_SHORT).show();
                    }
                }
            });
            builder.setPositiveButton("重新拍摄", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    startAiCheckIn(task);
                }
            });
            // 相册渠道出口：判定未通过时可换一张相册图重来（拍照路径不变）。
            builder.setNeutralButton("从相册选择", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    startAiGalleryCheckIn(task, null, false);
                }
            });
        } else {
            builder.setPositiveButton("知道了", null);
        }
        builder.show();
    }

    /** L1 预检拦截：明确给出理由（旧图 / 重复图），提供重拍与换相册图两个出口。 */
    private void showAiPrecheckBlocked(final Task task, VerificationPolicy.Precheck precheck,
                                       final File shot) {
        String title = VerificationPolicy.CODE_STALE.equals(precheck.code)
                ? "照片已过期" : "疑似重复照片";
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(precheck.reason)
                .setNegativeButton("取消", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        deleteQuietly(shot);
                    }
                })
                .setNeutralButton(PhotoChannel.GALLERY.label(), new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        deleteQuietly(shot);
                        startAiGalleryCheckIn(task, null, false);
                    }
                })
                .setPositiveButton("重新拍摄", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        deleteQuietly(shot);
                        startAiCheckIn(task);
                    }
                })
                .show();
    }

    /** 判定进行中的不可取消提示框（后台回调到达时关闭）。 */
    // ------------------------------------------------------------------
    // 待办 AI 判定：与打卡任务同构（相机 → L1 → GLM → 三分支 + 降级）。
    // ------------------------------------------------------------------

    /** 启动 AI 判定待办的完成流程：系统相机拍当前照。 */
    private void startAiTodoCheckIn(final Todo todo) {
        if (pendingShotFile != null) {
            Toast.makeText(this, "上一次拍照还在进行中", Toast.LENGTH_SHORT).show();
            return;
        }
        File shot = AiPhotoStore.newShotFile(this);
        Intent capture = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        Uri output = AiPhotoProvider.uriFor(this, shot);
        capture.putExtra(MediaStore.EXTRA_OUTPUT, output);
        capture.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            pendingShotTodoId = todo.id;
            pendingShotFile = shot;
            startActivityForResult(capture, REQUEST_AI_TODO_CHECKIN_PHOTO);
        } catch (Exception exception) {
            pendingShotFile = null;
            pendingShotTodoId = null;
            Toast.makeText(this, "无法启动系统相机：" + exception.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    /** 待办当前照返回：取消/失败即结束；成功则跑 L1 预检 → L2 判定。 */
    private void handleAiTodoCheckInPhotoResult(int resultCode) {
        final File shot = pendingShotFile;
        final String todoId = pendingShotTodoId;
        pendingShotFile = null;
        pendingShotTodoId = null;
        if (shot == null || todoId == null) return;
        if (resultCode != RESULT_OK || !shot.exists() || shot.length() == 0) {
            deleteQuietly(shot);
            Toast.makeText(this, "已取消拍照，待办未完成", Toast.LENGTH_SHORT).show();
            return;
        }
        final Todo todo = findTodo(todoId);
        if (todo == null) {
            deleteQuietly(shot);
            return;
        }
        // 相机返回 = 拍照渠道（新鲜度 + 去重都执行）。
        runAiTodoPrecheck(todo, shot, PhotoChannel.CAMERA);
    }

    /**
     * L1 离线预检（<b>渠道显式版</b>）：EXIF 新鲜度 + aHash 与该待办历史打卡照去重。
     * 相册渠道跳过新鲜度、保留去重，口径与任务打卡完全一致。
     */
    private void runAiTodoPrecheck(final Todo todo, final File shot, final PhotoChannel channel) {
        Date now = new Date();
        String dateKey = TaskLogic.dateKey(now);
        Long shotAt = AiImageShell.shotAtMillisOrNull(shot);
        String hashHex = AiImageShell.hashHexOrNull(shot);
        List<String> history = VerificationPolicy.dedupHashes(repository.getTodoAiRecords(todo.id));
        Long currentHash = hashHex == null ? null : ImageHash.fromHex(hashHex);
        VerificationPolicy.Precheck precheck = VerificationPolicy.precheck(channel, shotAt,
                now.getTime(), 0, currentHash, history);
        if (!precheck.passed) {
            Toast.makeText(this, precheck.reason, Toast.LENGTH_LONG).show();
            deleteQuietly(shot);
            return;
        }
        callAiTodoVerdict(todo, shot, hashHex == null ? "" : hashHex, dateKey, now);
    }

    /** L2：组装请求并异步调 GLM；降级铁律与打卡任务一致（完成 + 待复核，永不阻塞）。 */
    private void callAiTodoVerdict(final Todo todo, final File shot, final String hashHex,
                                   final String dateKey, final Date now) {
        final AiVerifyConfig config = AiVerifySettings.load(this);
        if (!todo.hasReferencePhoto()) {
            applyTodoVerdict(todo, shot, hashHex, dateKey, now,
                    VerificationPolicy.degraded("该待办尚未拍摄标准照"), null);
            return;
        }
        if (!VerificationPolicy.shouldCallModel(config.apiKey)) {
            applyTodoVerdict(todo, shot, hashHex, dateKey, now,
                    VerificationPolicy.degraded("未配置 API key"), null);
            return;
        }
        File reference = new File(todo.aiReferencePhoto);
        final String referenceUri = AiImageShell.dataUriOrNull(reference);
        final String currentUri = AiImageShell.dataUriOrNull(shot);
        if (referenceUri == null || currentUri == null) {
            applyTodoVerdict(todo, shot, hashHex, dateKey, now,
                    VerificationPolicy.degraded("照片读取失败"), null);
            return;
        }
        // 判定链版本：请求体在执行器线程内按链逐模型组装（N+11）。
        aiResultConsumed = false;
        showAiProgress(todo.title);
        GlmHttp.requestAsync(config, todo.title, todo.aiFeature, referenceUri, currentUri,
                new GlmHttp.Callback() {
            @Override
            public void onResult(final AiVerdict verdict, final String failureWhy) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        dismissAiProgress();
                        if (aiResultConsumed || isFinishing()) return;
                        aiResultConsumed = true;
                        VerificationPolicy.Outcome outcome = verdict == null
                                ? VerificationPolicy.degraded(failureWhy)
                                : VerificationPolicy.decideOrNull(verdict);
                        applyTodoVerdict(todo, shot, hashHex, dateKey, now, outcome, verdict);
                    }
                });
            }
        });
    }

    /** 待办判定结果落地：写判定历史 + 按需切完成态 + 展示。 */
    private void applyTodoVerdict(Todo todo, File shot, String hashHex, String dateKey, Date now,
                                  VerificationPolicy.Outcome outcome, AiVerdict verdict) {
        AiVerifyRecord record = new AiVerifyRecord(dateKey, now.getTime(),
                outcome.verdictKey, outcome.confidence,
                outcome.reason, outcome.degraded, false, outcome.complete, hashHex);
        repository.todoCheckInWithVerdict(todo.id, record, outcome.complete);
        repository.reload();
        render();
        if (outcome.complete) {
            showAiOutcomeTodoDialog(todo, outcome, verdict, false);
        } else {
            showAiOutcomeTodoDialog(todo, outcome, verdict, true);
        }
        deleteQuietly(shot);
    }

    /** 待办判定结果对话框：与打卡任务同构（fail 给重拍与申诉出口）。 */
    private void showAiOutcomeTodoDialog(final Todo todo, VerificationPolicy.Outcome outcome,
                                         AiVerdict verdict, boolean failed) {
        String title;
        if (failed) {
            title = "AI 判定未通过";
        } else if (outcome.needsReview) {
            title = "待办已完成（待复核）";
        } else {
            title = "AI 判定通过";
        }
        StringBuilder message = new StringBuilder();
        message.append(outcome.reason.isEmpty() ? "待办已完成。" : outcome.reason);
        if (verdict != null) {
            message.append("\n\n置信度：").append(verdict.confidence).append("%");
        }
        if (outcome.degraded) {
            message.append("\n\n本次未获得模型判定，已按本地预检完成待办，")
                    .append("可在「AI 判定设置」中检查 base_url / model / API key。");
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message.toString());
        if (failed) {
            builder.setNegativeButton("申诉并完成", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    if (repository.appealTodoLastVerdict(todo.id)) {
                        repository.reload();
                        render();
                        Toast.makeText(MainActivity.this, "已按申诉完成待办，记录标记为待复核",
                                Toast.LENGTH_SHORT).show();
                    }
                }
            });
            builder.setPositiveButton("重新拍摄", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    startAiTodoCheckIn(todo);
                }
            });
            // 相册渠道出口：判定未通过时可换一张相册图重来（拍照路径不变）。
            builder.setNeutralButton("从相册选择", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    startAiGalleryCheckIn(null, todo, true);
                }
            });
        } else {
            builder.setPositiveButton("知道了", null);
        }
        builder.show();
    }

    private void showAiProgress(String itemLabel) {
        dismissAiProgress();
        aiProgressDialog = new AlertDialog.Builder(this)
                .setTitle("AI 判定中")
                .setMessage("正在把照片发送给模型判定「" + itemLabel + "」，最多等待 20 秒……")
                .setCancelable(false)
                .create();
        aiProgressDialog.show();
    }

    private void showAiProgress(Task task) {
        showAiProgress(task.name);
    }

    private void dismissAiProgress() {
        if (aiProgressDialog == null) return;
        try {
            if (aiProgressDialog.isShowing()) aiProgressDialog.dismiss();
        } catch (IllegalArgumentException ignored) {
            // Activity 已销毁时窗口句柄失效，忽略。
        }
        aiProgressDialog = null;
    }

    /** 判定结束/取消后清理私有目录里的临时照片，避免目录无限增长。 */
    private static void deleteQuietly(File file) {
        if (file == null) return;
        try {
            if (file.exists() && !file.delete()) {
                file.deleteOnExit();
            }
        } catch (SecurityException ignored) {
            // 删除失败不影响打卡结果。
        }
    }

    /** AI 判定历史：时间 / 结论 / 置信度 / 理由 / 降级 / 申诉，供展示与审计。 */
    private void showAiVerdictHistory(Task task) {
        List<AiVerifyRecord> records = repository.getAiRecords(task.id);
        if (records.isEmpty()) {
            new AlertDialog.Builder(this)
                    .setTitle("AI 判定记录")
                    .setMessage("「" + task.name + "」还没有判定记录。")
                    .setPositiveButton("知道了", null)
                    .show();
            return;
        }
        SimpleDateFormat time = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA);
        StringBuilder builder = new StringBuilder();
        // 倒序：最近的判定在最上面。
        for (int i = records.size() - 1; i >= 0; i--) {
            AiVerifyRecord record = records.get(i);
            builder.append(time.format(new Date(record.atMillis)))
                    .append("  ").append(verdictLabel(record.verdict))
                    .append("  ").append(record.confidence).append("%");
            if (record.degraded) builder.append("  [降级]");
            if (record.appealed) builder.append("  [已申诉]");
            builder.append(record.completed ? "  已完成" : "  未完成");
            if (!record.reason.isEmpty()) builder.append('\n').append(record.reason);
            if (i > 0) builder.append("\n\n");
        }
        new AlertDialog.Builder(this)
                .setTitle("AI 判定记录（" + records.size() + " 条）")
                .setMessage(builder.toString())
                .setPositiveButton("关闭", null)
                .show();
    }

    private static String verdictLabel(String verdict) {
        if (AiVerdict.Verdict.PASS.key().equals(verdict)) return "通过";
        if (AiVerdict.Verdict.FAIL.key().equals(verdict)) return "未通过";
        return "待复核";
    }

    /** 任务标题的 AI 标记：让列表一眼看出哪些任务需要拍照判定。 */
    private static String aiTitleLabel(Task task) {
        return task.isAiPhoto() ? task.name + " 📷" : task.name;
    }

    /**
     * AI 判定设置（BYOK）：base_url / model / api key，默认预置智谱模板。
     *
     * <p>key 只存本机 SharedPreferences 的独立键 {@code ai-verify.v1}；对话框内含
     * 隐私明示——照片会上传到用户填写的模型服务商。</p>
     */
    private void showAiVerifySettings() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        box.setPadding(pad, pad, pad, pad / 2);

        final AiVerifyConfig current = AiVerifySettings.load(this);
        final EditText baseUrlInput = settingsField(box, "base_url（OpenAI 兼容网关，填到 /v4"
                        + " 或直接粘贴完整 chat/completions 地址均可）",
                AiVerifyConfig.DEFAULT_BASE_URL);
        baseUrlInput.setText(current.baseUrl.isEmpty() ? current.effectiveBaseUrl() : current.baseUrl);
        // 判定链（N+11）：最多 3 个模型，按序失败转移；空位 = 跳过该位。
        final List<String> currentChain = current.models.isEmpty()
                ? AiVerifyConfig.defaultModels() : current.models;
        final EditText model1Input = settingsField(box, "判定模型 1（留空则用默认 "
                        + AiVerifyConfig.DEFAULT_MODEL_1 + "）", AiVerifyConfig.DEFAULT_MODEL_1);
        model1Input.setText(chainAt(currentChain, 0));
        final EditText model2Input = settingsField(box, "判定模型 2（可留空 = 跳过该位）",
                AiVerifyConfig.DEFAULT_MODEL_2);
        model2Input.setText(chainAt(currentChain, 1));
        final EditText model3Input = settingsField(box, "判定模型 3（可留空 = 跳过该位）",
                AiVerifyConfig.DEFAULT_MODEL_3);
        model3Input.setText(chainAt(currentChain, 2));
        TextView chainHint = new TextView(this);
        chainHint.setTextSize(12);
        chainHint.setTextColor(getColor(R.color.textSecondary));
        chainHint.setText("判定链建议全部使用视觉模型；文本模型收到图片会失败并自动跳下一个。"
                + "判定时按 1→2→3 顺序尝试，单个模型失败（网络/限流/不支持图片）自动转移下一个；"
                + "key 无效（401/403）不转移，直接走降级。");
        box.addView(chainHint, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        final EditText keyInput = settingsField(box, "API key（只存本机，不上传）", "");
        keyInput.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        keyInput.setHint(current.apiKey.isEmpty() ? "未配置（打卡走降级：本地预检 + 待复核）"
                : "已保存 " + current.maskedKey() + "，留空则保持不变");

        TextView notice = new TextView(this);
        notice.setTextSize(12);
        notice.setTextColor(getColor(R.color.textSecondary));
        notice.setText("隐私明示：启用 AI 拍照判定后，打卡照片与特征描述会上传到你填写的模型服务商"
                + "用于生成判定结果；照片同时会在本机私有目录临时保存以便判定。"
                + "API key 仅保存在本机 SharedPreferences，不会写入任务数据或备份文件。"
                + "未配置 key 或网络不可用时，打卡不会失败——按本地预检完成并标记「待复核」。");
        box.addView(notice, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        final TextView errorText = new TextView(this);
        errorText.setTextSize(13);
        errorText.setTextColor(getColor(R.color.statusMissedText));
        box.addView(errorText, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        // 「测试连接」：发最小 chat 请求（max_tokens=1），直接验证 base_url/model/key 三件套。
        final Button probeButton = new Button(this);
        probeButton.setText("测试连接");
        probeButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                AiVerifyConfig candidate = candidateConfig(current, baseUrlInput,
                        model1Input, model2Input, model3Input, keyInput);
                if (!candidate.isConfigured()) {
                    errorText.setText("未配置 API key，无法测试；打卡将走降级（本地预检 + 待复核）");
                    return;
                }
                testAiConnection(probeButton, errorText, candidate);
            }
        });
        box.addView(probeButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));


        ScrollView scroll = new ScrollView(this);
        scroll.addView(box);

        // 与任务编辑器同一风格：先 show，再覆盖「保存」按钮的点击，
        // 校验不通过时保留用户已输入的内容，不重建对话框。
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("AI 判定设置")
                .setView(scroll)
                .setNegativeButton("取消", null)
                .setPositiveButton("保存", null)
                .create();
        dialog.show();
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                // key 留空 = 保持已保存的值（避免每次重填）。
                AiVerifyConfig next = candidateConfig(current, baseUrlInput,
                        model1Input, model2Input, model3Input, keyInput);
                List<String> errors = next.validate();
                if (!errors.isEmpty()) {
                    errorText.setText(String.join("；", errors));
                    return;
                }
                AiVerifySettings.save(MainActivity.this, next);
                dialog.dismiss();
                Toast.makeText(MainActivity.this, next.isConfigured()
                        ? "AI 判定已启用（判定链 " + String.join(" → ", next.effectiveModels()) + "）"
                        : "已保存：未配置 key，打卡将走本地预检 + 待复核",
                        Toast.LENGTH_SHORT).show();
            }
        });
    }

    /**
     * 从设置对话框输入组装候选配置（N+11 判定链）：三行模型输入按序进链，
     * 空位剔除（{@link com.checktodo.app.domain.ModelChain#sanitize}）；全空 → 回落默认链。
     */
    private AiVerifyConfig candidateConfig(AiVerifyConfig current, EditText baseUrlInput,
                                           EditText model1, EditText model2, EditText model3,
                                           EditText keyInput) {
        String baseUrl = baseUrlInput.getText().toString().trim();
        List<String> chain = new ArrayList<>();
        chain.add(model1.getText().toString().trim());
        chain.add(model2.getText().toString().trim());
        chain.add(model3.getText().toString().trim());
        if (com.checktodo.app.domain.ModelChain.sanitize(chain).isEmpty()) {
            chain = AiVerifyConfig.defaultModels();
        }
        String typedKey = keyInput.getText().toString().trim();
        return AiVerifyConfig.ofChain(baseUrl, chain, typedKey.isEmpty() ? current.apiKey : typedKey);
    }

    /** 链位取值（越界 → 空串，输入框显示 hint 默认值）。 */
    private static String chainAt(List<String> chain, int index) {
        return chain != null && index < chain.size() && chain.get(index) != null ? chain.get(index) : "";
    }

    // ------------------------------------------------------------------
    // AI 周报（功能一）
    // ------------------------------------------------------------------

    /**
     * 「AI 周报」对话框：列出本地保存的历史周报（点击看全文）+「生成本周报告」按钮。
     *
     * <p>未配置 API key → 只显示提示，不发请求（避免白白等 20 秒超时）。</p>
     */
    private void showWeeklyReports() {
        final AiVerifyConfig config = AiVerifySettings.load(this);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        box.setPadding(pad, pad, pad, pad / 2);

        final TextView hintText = new TextView(this);
        hintText.setTextSize(12);
        hintText.setTextColor(getColor(R.color.textSecondary));
        hintText.setText(config.isConfigured()
                ? "每周一 00:00 自动生成上一周的周报并存到本机（最多保留 "
                        + com.checktodo.app.domain.WeeklyReport.HISTORY_LIMIT + " 条）。"
                : "未配置 API key：先在「AI 判定设置」里填 key，周报才能生成。"
                        + "周报只是附加功能，打卡与提醒不受影响。");
        box.addView(hintText, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        final LinearLayout listBox = new LinearLayout(this);
        listBox.setOrientation(LinearLayout.VERTICAL);
        box.addView(listBox, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        renderWeeklyReportList(listBox, WeeklyReportStore.load(this));

        ScrollView scroll = new ScrollView(this);
        scroll.addView(box);

        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.menu_ai_weekly)
                .setView(scroll)
                .setNeutralButton("生成中…", null)
                .setNegativeButton("关闭", null)
                .create();
        dialog.show();
        final Button generateButton = dialog.getButton(DialogInterface.BUTTON_NEUTRAL);
        generateButton.setText("生成本周报告");
        generateButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (!config.isConfigured()) {
                    hintText.setText("未配置 API key，无法生成周报；请在「AI 判定设置」里填写后再试。");
                    return;
                }
                generateThisWeekReport(generateButton, hintText, listBox);
            }
        });
    }

    /**
     * 手动生成本周报告（本周一 → 今天）：网络在 GlmHttp 的单线程执行器上，
     * 期间按钮置灰防重复点击，结果 runOnUiThread 回主线程后刷新列表并弹全文。
     */
    private void generateThisWeekReport(final Button generateButton, final TextView hintText,
                                        final LinearLayout listBox) {
        generateButton.setEnabled(false);
        generateButton.setText("生成中…（单次最多等待 90 秒，网络失败将重试）");
        hintText.setText("正在统计本周打卡数据并请求模型生成周报……");
        final Date now = new Date();
        final com.checktodo.app.domain.WeeklyReport.Week week =
                com.checktodo.app.domain.WeeklyReport.weekUntil(now);
        final String prompt = com.checktodo.app.domain.WeeklyReport.WeeklyReportPrompt.build(
                WeeklyReportStore.summarize(this, week, now));
        GlmHttp.requestTextAsync(AiVerifySettings.load(this), prompt, new GlmHttp.TextCallback() {
            @Override
            public void onResult(final String text, final String failureWhy) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        generateButton.setEnabled(true);
                        generateButton.setText("生成本周报告");
                        String cleanText = com.checktodo.app.domain.WeeklyReport.stripThinking(text);
                        if (cleanText.isEmpty()) {
                            // 剥离思考块后无正文（生成截断）→ 与失败同口径展示。
                            hintText.setText("周报生成失败："
                                    + (failureWhy == null || failureWhy.isEmpty() ? "正文为空" : failureWhy));
                            return;
                        }
                        WeeklyReportStore.Entry entry = new WeeklyReportStore.Entry(
                                week.startKey, week.endKey, System.currentTimeMillis(), cleanText,
                                AiVerifySettings.load(MainActivity.this).effectiveModel());
                        WeeklyReportStore.append(MainActivity.this, entry);
                        renderWeeklyReportList(listBox, WeeklyReportStore.load(MainActivity.this));
                        hintText.setText("已生成本周报告（" + week.label() + "）。");
                        showWeeklyReportDetail(entry);
                    }
                });
            }
        });
    }

    /** 历史列表：每条一行「区间（生成时刻）」+ 摘要，点击看全文。 */
    private void renderWeeklyReportList(LinearLayout listBox, List<WeeklyReportStore.Entry> entries) {
        listBox.removeAllViews();
        if (entries == null || entries.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setTextSize(13);
            empty.setTextColor(getColor(R.color.textSecondary));
            empty.setPadding(0, dp(8), 0, dp(8));
            empty.setText("还没有周报。周一 00:00 会自动生成上一周的，也可以点下面的按钮生成本周的。");
            listBox.addView(empty);
            return;
        }
        for (final WeeklyReportStore.Entry entry : entries) {
            TextView row = new TextView(this);
            row.setTextSize(14);
            row.setTextColor(getColor(R.color.textPrimary));
            row.setPadding(0, dp(10), 0, dp(10));
            row.setText(entry.weekLabel() + "\n" + entry.excerpt());
            row.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    showWeeklyReportDetail(entry);
                }
            });
            listBox.addView(row);
        }
    }

    /** 周报全文对话框（可选中复制）。 */
    private void showWeeklyReportDetail(WeeklyReportStore.Entry entry) {
        TextView body = new TextView(this);
        body.setTextSize(14);
        body.setTextColor(getColor(R.color.textPrimary));
        body.setTextIsSelectable(true);
        int pad = dp(20);
        body.setPadding(pad, pad, pad, pad);
        body.setText(entry.body);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(body);
        new AlertDialog.Builder(this)
                .setTitle(entry.titleWithTime())
                .setView(scroll)
                .setPositiveButton("关闭", null)
                .show();
    }

    /**
     * 「测试连接」：后台发最小探针请求（GlmHttp.requestProbe），期间按钮置灰防重复点击，
     * 结果回 UI 线程展示（成功含服务端回报的模型名；失败含状态码与 endpoint，便于一眼定位）。
     */
    private void testAiConnection(final Button probeButton, final TextView resultView,
                                  final AiVerifyConfig config) {
        probeButton.setEnabled(false);
        resultView.setText("测试中…（最长 20 秒）");
        GlmHttp.requestProbe(config, new GlmHttp.ProbeCallback() {
            @Override
            public void onResult(final boolean ok, final String modelName, final String message) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        probeButton.setEnabled(true);
                        resultView.setTextColor(getColor(ok
                                ? R.color.statusDoneText : R.color.statusMissedText));
                        resultView.setText((ok ? "✔ " : "✘ ") + message);
                    }
                });
            }
        });
    }

    /** 设置对话框里的一个「标签 + 输入框」组合。 */
    private EditText settingsField(LinearLayout box, String label, String hint) {
        TextView title = new TextView(this);
        title.setText(label);
        title.setTextSize(13);
        title.setTextColor(getColor(R.color.textSecondary));
        title.setTypeface(Typeface.DEFAULT_BOLD);
        box.addView(title, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        EditText input = new EditText(this);
        input.setHint(hint);
        input.setSingleLine(true);
        input.setTextSize(14);
        box.addView(input, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        return input;
    }

    private void updateTaskItem(String taskId) {
        Task task = findTask(taskId);
        View item = taskItemViews.get(taskId);
        if (task == null || item == null) {
            render();
            return;
        }
        bindTaskView(item, task, new Date());
    }

    private Task findTask(String taskId) {
        for (Task task : repository.getTasks()) if (task.id.equals(taskId)) return task;
        return null;
    }

    private Todo findTodo(String todoId) {
        for (Todo todo : repository.getTodos()) if (todo.id.equals(todoId)) return todo;
        return null;
    }

    private void renderTodos() {
        List<Todo> todos = repository.getTodos();
        if (todos.isEmpty()) {
            View empty = LayoutInflater.from(this).inflate(R.layout.empty_task, taskList, false);
            ((TextView) empty.findViewById(R.id.empty_text)).setText("还没有待办，先创建一个吧");
            taskList.addView(empty);
            return;
        }

        pendingHeader = addTodoGroup("待完成", TodoLogic.pending(todos).size(), pendingExpanded, new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                pendingExpanded = !pendingExpanded;
                render();
            }
        });
        if (pendingExpanded) {
            for (Todo todo : TodoLogic.pending(todos)) addTodoItem(todo);
        }

        completedHeader = addTodoGroup("已完成", TodoLogic.completed(todos).size(), completedExpanded, new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                completedExpanded = !completedExpanded;
                render();
            }
        });
        if (completedExpanded) {
            for (Todo todo : TodoLogic.completed(todos)) addTodoItem(todo);
        }
    }

    private View addTodoGroup(String title, int count, boolean expanded, View.OnClickListener listener) {
        View header = inflater.inflate(R.layout.item_todo_group, taskList, false);
        TextView titleView = header.findViewById(R.id.todo_group_title);
        titleView.setText(title + "（" + count + "）");
        if ("待完成".equals(title)) pendingHeaderTitle = titleView;
        if ("已完成".equals(title)) completedHeaderTitle = titleView;
        ((TextView) header.findViewById(R.id.todo_group_state)).setText(expanded ? "收起" : "展开");
        header.setOnClickListener(listener);
        taskList.addView(header);
        return header;
    }

    private void addTodoItem(final Todo todo) {
        View item = inflater.inflate(R.layout.item_todo, taskList, false);
        todoItemViews.put(todo.id, item);
        bindTodoView(item, todo);
        taskList.addView(item);
    }

    /** 绑定/刷新单条待办：左侧圆形勾选框、状态标签、截止时间、花费时长与溢出菜单。 */
    private void bindTodoView(final View item, final Todo todo) {
        TextView checkbox = item.findViewById(R.id.todo_checkbox);
        TextView title = item.findViewById(R.id.todo_title);
        TextView status = item.findViewById(R.id.todo_status);
        TextView time = item.findViewById(R.id.todo_time);
        TextView duration = item.findViewById(R.id.todo_duration);
        View overflow = item.findViewById(R.id.todo_overflow);

        TodoStatus todoStatus = TodoLogic.status(todo, System.currentTimeMillis());
        boolean done = todoStatus == TodoStatus.COMPLETED;
        item.setTag(todo);
        checkbox.setText(done ? "✓" : "");
        checkbox.setBackgroundResource(done ? R.drawable.todo_checkbox_done : R.drawable.todo_checkbox_empty);
        checkbox.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                onTodoCheckClicked(todo);
            }
        });

        title.setText(todo.title);
        title.setAlpha(done ? 0.6f : 1f);
        title.setTextColor(todoStatus == TodoStatus.EXPIRED
                ? getColor(R.color.statusMissedText) : getColor(R.color.textPrimary));
        status.setText(todoStatusLabel(todoStatus));
        status.setBackgroundResource(todoStatusBackground(todoStatus));
        status.setTextColor(todoStatusStatusTextColor(todoStatus));
        String deadlineText = "截止 " + formatDateTime(todo.endTime);
        if (todo.completed && todo.isAiPhoto() && repository.latestTodoRecordNeedsReview(todo.id)) {
            deadlineText += "（待复核）";
        }
        time.setText(deadlineText);
        duration.setText("花费 " + TodoLogic.formatDuration(todo.durationMinutes));

        overflow.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                PopupMenu menu = new PopupMenu(MainActivity.this, view);
                menu.getMenu().add(0, 1, 0, "编辑");
                menu.getMenu().add(0, 2, 1, "删除");
                menu.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
                    @Override
                    public boolean onMenuItemClick(android.view.MenuItem menuItem) {
                        if (menuItem.getItemId() == 1) {
                            showTodoEditor(todo);
                        } else if (menuItem.getItemId() == 2) {
                            confirmDeleteTodo(todo);
                        }
                        return true;
                    }
                });
                menu.show();
            }
        });
    }

    /**
     * 勾选/撤销待办：只更新该条视图并在分组间移动，或目标分组收起时直接移除，
     * 不整页重建列表；分组计数同步刷新。
     */
    private void onTodoCheckClicked(Todo todo) {
        boolean next = !todo.completed;
        // AI 拍照待办：完成动作必须经「选来源 → L1 预检 → GLM 判定」，卡片与列表都不直接写库。
        if (next && todo.isAiPhoto()) {
            pickAiCheckInSource(null, todo, true);
            return;
        }
        if (!repository.setTodoCompleted(todo.id, next)) return;
        Todo updated = findTodo(todo.id);
        View item = todoItemViews.get(todo.id);
        if (updated == null || item == null) {
            render();
            return;
        }
        bindTodoView(item, updated);
        boolean destinationExpanded = next ? completedExpanded : pendingExpanded;
        if (destinationExpanded) {
            moveTodoItem(item, next);
        } else {
            taskList.removeView(item);
            todoItemViews.remove(todo.id);
        }
        updateTodoGroupCounts();
    }

    private void moveTodoItem(View item, boolean toCompleted) {
        View anchor = toCompleted ? completedHeader : pendingHeader;
        if (anchor == null) return;
        Todo todo = (Todo) item.getTag();
        taskList.removeView(item);
        // 插入目标分组内按截止时间升序的正确位置（遇到分组头或更晚截止的条目前插入）。
        int insertAt = taskList.getChildCount();
        for (int i = taskList.indexOfChild(anchor) + 1; i < taskList.getChildCount(); i++) {
            Object tag = taskList.getChildAt(i).getTag();
            if (!(tag instanceof Todo)) {
                insertAt = i;
                break;
            }
            if (((Todo) tag).endTime > todo.endTime) {
                insertAt = i;
                break;
            }
        }
        taskList.addView(item, insertAt);
    }

    private void updateTodoGroupCounts() {
        List<Todo> todos = repository.getTodos();
        if (pendingHeaderTitle != null) {
            pendingHeaderTitle.setText("待完成（" + TodoLogic.pending(todos).size() + "）");
        }
        if (completedHeaderTitle != null) {
            completedHeaderTitle.setText("已完成（" + TodoLogic.completed(todos).size() + "）");
        }
    }

    // ------------------------------------------------------------------
    // 问题 1：任务编辑器时间改为 TimePickerDialog
    // ------------------------------------------------------------------

    private void showTaskEditor(final Task task) {
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_task_editor, null, false);
        final EditText nameInput = view.findViewById(R.id.input_name);
        final TextView startValue = view.findViewById(R.id.input_start);
        final TextView endValue = view.findViewById(R.id.input_end);
        final Button startButton = view.findViewById(R.id.button_start_time);
        final Button endButton = view.findViewById(R.id.button_end_time);
        final TextView errorText = view.findViewById(R.id.error_text);
        final LinearLayout weekdayBox = view.findViewById(R.id.weekday_box);
        final Button[] requiredChips = {
                view.findViewById(R.id.chip_required_15),
                view.findViewById(R.id.chip_required_30),
                view.findViewById(R.id.chip_required_60),
                view.findViewById(R.id.chip_required_120)
        };
        final int[] requiredPresets = {15, 30, 60, 120};
        final EditText requiredCustom = view.findViewById(R.id.input_required_custom);
        final TextView reminderPreview = view.findViewById(R.id.text_reminder_preview);
        final CheckBox aiCheckBox = view.findViewById(R.id.check_ai_verify);
        final LinearLayout aiBox = view.findViewById(R.id.box_ai_verify);
        // 标准照按钮与状态行是字段（相机返回后要回填文案），不是局部 final 变量。
        aiReferenceButton = view.findViewById(R.id.button_ai_reference);
        aiReferenceGalleryButton = view.findViewById(R.id.button_ai_reference_gallery);
        aiConfigState = view.findViewById(R.id.text_ai_config_state);
        final EditText aiFeatureInput = view.findViewById(R.id.input_ai_feature);
        // 编辑器生命周期内先沿用任务已有的标准照；重拍会覆盖它。
        editorReferencePhoto = task == null ? "" : task.aiReferencePhoto;

        nameInput.setFilters(new InputFilter[]{new InputFilter.LengthFilter(40)});
        final boolean[] weekdays = new boolean[7];
        // 时间只保存在这两个字符串里，由选择器回填，界面上没有任何可直接编辑的输入框。
        final String[] times = new String[2];
        if (task == null) {
            weekdays[0] = true;
            weekdays[2] = true;
            weekdays[4] = true;
            times[0] = "08:00";
            times[1] = "22:00";
        } else {
            System.arraycopy(task.weekdays, 0, weekdays, 0, 7);
            nameInput.setText(task.name);
            times[0] = task.startTime;
            times[1] = task.endTime;
            aiCheckBox.setChecked(task.isAiPhoto());
            aiFeatureInput.setText(task.aiFeature);
        }
        startValue.setText(times[0]);
        endValue.setText(times[1]);

        // AI 判定区：勾选后展开标准照 + 特征描述；未勾选隐藏（普通任务数据零变化）。
        final Runnable syncAiSection = new Runnable() {
            @Override
            public void run() {
                boolean enabled = aiCheckBox.isChecked();
                aiBox.setVisibility(enabled ? View.VISIBLE : View.GONE);
                if (!enabled) return;
                AiVerifyConfig config = AiVerifySettings.load(MainActivity.this);
                String keyState = config.isConfigured()
                        ? "模型 " + config.effectiveModel() + "（key 已配置 " + config.maskedKey() + "）"
                        : "未配置 API key：打卡按本地预检完成并标记「待复核」";
                String photoState = editorReferencePhoto.isEmpty()
                        ? "标准照：未拍摄（打卡时将走降级路径）" : "标准照：已拍摄";
                aiConfigState.setText(photoState + "\n" + keyState);
                aiReferenceButton.setText(editorReferencePhoto.isEmpty()
                        ? "拍摄标准照" : "重拍标准照");
            }
        };
        aiCheckBox.setOnCheckedChangeListener((buttonView, isChecked) -> syncAiSection.run());
        aiReferenceButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (pendingReferenceFile != null || pendingGalleryPick) {
                    Toast.makeText(MainActivity.this, "上一次拍照还在进行中", Toast.LENGTH_SHORT).show();
                    return;
                }
                File reference = AiPhotoStore.newReferenceFile(MainActivity.this);
                Intent capture = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
                capture.putExtra(MediaStore.EXTRA_OUTPUT,
                        AiPhotoProvider.uriFor(MainActivity.this, reference));
                capture.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        | Intent.FLAG_GRANT_READ_URI_PERMISSION);
                try {
                    pendingReferenceForTodo = false;
                    pendingReferenceFile = reference;
                    startActivityForResult(capture, REQUEST_AI_REFERENCE_PHOTO);
                } catch (Exception exception) {
                    pendingReferenceFile = null;
                    Toast.makeText(MainActivity.this, "无法启动系统相机：" + exception.getMessage(),
                            Toast.LENGTH_LONG).show();
                }
            }
        });
        // 相册渠道（N+12）：标准照也可以从相册选图；结果拷进私有目录后回填编辑器状态，
        // 与拍照路径完全一致（保存时随任务持久化）。
        if (aiReferenceGalleryButton != null) {
            aiReferenceGalleryButton.setText(PhotoChannel.GALLERY.label());
            aiReferenceGalleryButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    startAiGalleryReference(false);
                }
            });
        }
        syncAiSection.run();

        final Runnable syncTimeLabels = new Runnable() {
            @Override
            public void run() {
                startButton.setText("开始时间 " + times[0]);
                endButton.setText("结束时间 " + times[1]);
            }
        };
        syncTimeLabels.run();

        final int[] requiredMinutes = {task == null
                ? Task.DEFAULT_REQUIRED_MINUTES : ReminderLogic.requiredMinutes(task)};
        if (task != null && requiredMinutes[0] > 0 && !matchesPreset(requiredMinutes[0], requiredPresets)) {
            requiredCustom.setText(String.valueOf(requiredMinutes[0]));
        }
        final boolean[] suppressCustomEvent = {false};
        final Runnable syncRequired = new Runnable() {
            @Override
            public void run() {
                for (int i = 0; i < requiredChips.length; i++) {
                    requiredChips[i].setSelected(requiredMinutes[0] == requiredPresets[i]);
                }
                reminderPreview.setText(reminderPreviewText(times[0], times[1], requiredMinutes[0]));
            }
        };
        syncRequired.run();

        for (int i = 0; i < requiredChips.length; i++) {
            final int preset = requiredPresets[i];
            requiredChips[i].setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    requiredMinutes[0] = preset;
                    suppressCustomEvent[0] = true;
                    requiredCustom.setText("");
                    suppressCustomEvent[0] = false;
                    syncRequired.run();
                }
            });
        }
        requiredCustom.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                if (suppressCustomEvent[0]) return;
                int value = parseInt(s.toString(), 0);
                if (value > 0) {
                    requiredMinutes[0] = value;
                    syncRequired.run();
                }
            }
        });

        startButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickTime("选择开始时间", times[0], new TimePicked() {
                    @Override
                    public void onPicked(int hour, int minute) {
                        times[0] = formatHourMinute(hour, minute);
                        startValue.setText(times[0]);
                        syncTimeLabels.run();
                        syncRequired.run();
                    }
                });
            }
        });
        endButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickTime("选择结束时间", times[1], new TimePicked() {
                    @Override
                    public void onPicked(int hour, int minute) {
                        times[1] = formatHourMinute(hour, minute);
                        endValue.setText(times[1]);
                        syncTimeLabels.run();
                        syncRequired.run();
                    }
                });
            }
        });

        for (int i = 0; i < 7; i++) {
            // 重复日期纵向排布：上=勾选框，下=文字；整列作为点击热区（>=44dp 高）。
            final LinearLayout column = new LinearLayout(this);
            column.setOrientation(LinearLayout.VERTICAL);
            column.setGravity(Gravity.CENTER_HORIZONTAL);
            column.setMinimumHeight(dp(44));
            TypedValue background = new TypedValue();
            getTheme().resolveAttribute(android.R.attr.selectableItemBackground, background, true);
            column.setBackgroundResource(background.resourceId);

            final CheckBox checkBox = new CheckBox(this);
            final int index = i;
            checkBox.setButtonDrawable(R.drawable.checkbox_weekday);
            checkBox.setMinWidth(0);
            checkBox.setMinimumWidth(0);
            checkBox.setMinHeight(0);
            checkBox.setMinimumHeight(0);
            checkBox.setPadding(0, dp(8), 0, 0);
            checkBox.setClickable(false);
            column.addView(checkBox, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            final TextView label = new TextView(this);
            label.setText(WEEKDAY_LABELS[i]);
            label.setGravity(Gravity.CENTER);
            label.setTextSize(13);
            label.setPadding(0, dp(5), 0, dp(9));
            column.addView(label, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            checkBox.setOnCheckedChangeListener((buttonView, isChecked) -> {
                weekdays[index] = isChecked;
                label.setTextColor(getColor(isChecked ? R.color.primary : R.color.textPrimary));
                label.setTypeface(isChecked ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            });
            column.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    checkBox.toggle();
                }
            });
            checkBox.setChecked(weekdays[index]);

            LinearLayout.LayoutParams columnParams = new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            weekdayBox.addView(column, columnParams);
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle(task == null ? "新建任务" : "编辑任务")
                .setView(view)
                .setNegativeButton("取消", null)
                .setPositiveButton("保存", null);
        final AlertDialog dialog = builder.create();
        dialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(DialogInterface d) {
                // 编辑器关闭后不再回填标准照文案，避免写进下一个对话框的视图。
                aiReferenceButton = null;
                aiReferenceGalleryButton = null;
                aiConfigState = null;
            }
        });
        dialog.show();
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                int customRequired = parseInt(requiredCustom.getText().toString(), requiredMinutes[0]);
                if (customRequired != requiredMinutes[0]) {
                    requiredMinutes[0] = customRequired;
                }
                List<String> errors = TaskLogic.validateTask(nameInput.getText().toString(), weekdays,
                        times[0], times[1], requiredMinutes[0]);
                if (!errors.isEmpty()) {
                    errorText.setText(String.join("；", errors));
                    return;
                }
                // 纯函数返回值纪律：saveTask / saveVerifyConfig 都返回新对象，接收它再刷新。
                boolean aiEnabled = aiCheckBox.isChecked();
                Task saved = repository.saveTask(task == null ? null : task.id,
                        nameInput.getText().toString(), weekdays,
                        times[0], times[1], requiredMinutes[0]);
                repository.saveVerifyConfig(saved.id,
                        aiEnabled ? VerifyMode.AI_PHOTO : VerifyMode.NONE,
                        aiEnabled ? aiFeatureInput.getText().toString() : "",
                        aiEnabled ? editorReferencePhoto : "");
                dialog.dismiss();
                render();
            }
        });
    }

    private static boolean matchesPreset(int value, int[] presets) {
        for (int preset : presets) {
            if (value == preset) return true;
        }
        return false;
    }

    /** 编辑器内实时展示统一公式：提醒 = 截止时间 - 完成所需时间 - 1 小时缓冲。 */
    private static String reminderPreviewText(String start, String end, int minutes) {
        Task preview = new Task("preview", "", new boolean[7], start, end, null, 0L, minutes);
        long reminder = ReminderLogic.computeTaskReminderTime(preview, new Date());
        if (reminder == ReminderLogic.NOT_SCHEDULED) {
            return "提醒时刻：--";
        }
        return "提醒时刻：" + ReminderLogic.formatTimeOfDay(reminder)
                + "（所需 " + minutes + " 分钟，缓冲 1 小时）";
    }

    /** TimePickerDialog 回调接口，避免重复写匿名类。 */
    private interface TimePicked {
        void onPicked(int hour, int minute);
    }

    /**
     * 统一的时间选择：setIs24HourView(true)，只允许通过选择器取值。
     */
    private void pickTime(String title, String current, final TimePicked callback) {
        int hour = 8;
        int minute = 0;
        if (current != null && current.matches("\\d{2}:\\d{2}")) {
            hour = Integer.parseInt(current.substring(0, 2));
            minute = Integer.parseInt(current.substring(3));
        }
        TimePickerDialog dialog = new TimePickerDialog(this, new TimePickerDialog.OnTimeSetListener() {
            @Override
            public void onTimeSet(android.widget.TimePicker view, int hourOfDay, int minuteOfHour) {
                callback.onPicked(hourOfDay, minuteOfHour);
            }
        }, hour, minute, true);
        dialog.setTitle(title);
        dialog.show();
    }

    private static String formatHourMinute(int hour, int minute) {
        return String.format(Locale.US, "%02d:%02d", hour, minute);
    }

    private void showTodoEditor(final Todo todo) {
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_todo_editor, null, false);
        final EditText titleInput = view.findViewById(R.id.input_todo_title);
        final Button startDateButton = view.findViewById(R.id.button_start_date);
        final Button startTimeButton = view.findViewById(R.id.button_start_time);
        final Button endDateButton = view.findViewById(R.id.button_end_date);
        final Button endTimeButton = view.findViewById(R.id.button_end_time);
        final EditText durationHours = view.findViewById(R.id.input_duration_hours);
        final EditText durationMinutes = view.findViewById(R.id.input_duration_minutes);
        final TextView errorText = view.findViewById(R.id.todo_error_text);

        // AI 判定（待办）：勾选后启用拍照判定；标准照与特征描述随待办保存。
        editorTodoReferencePhoto = todo == null ? "" : todo.aiReferencePhoto;
        LinearLayout editorBox = (LinearLayout) ((android.widget.ScrollView) view).getChildAt(0);
        final CheckBox aiTodoCheck = new CheckBox(this);
        aiTodoCheck.setText("AI 拍照判定（完成时拍照，由模型判定）");
        if (todo != null) aiTodoCheck.setChecked(todo.isAiPhoto());
        final LinearLayout aiTodoBox = new LinearLayout(this);
        aiTodoBox.setOrientation(LinearLayout.VERTICAL);
        aiTodoBox.setVisibility(aiTodoCheck.isChecked() ? View.VISIBLE : View.GONE);
        aiTodoReferenceButton = new Button(this);
        aiTodoReferenceButton.setText(editorTodoReferencePhoto.isEmpty()
                ? "拍摄标准照" : "重拍标准照（已拍摄）");
        final EditText aiTodoFeatureInput = new EditText(this);
        aiTodoFeatureInput.setHint("打卡特征描述（给 AI 的证据，如：房间已打扫、桌面收拾干净）");
        aiTodoFeatureInput.setText(todo == null ? "" : todo.aiFeature);
        aiTodoFeatureInput.setTextSize(13);
        // 相册渠道（N+12）：「从相册选择」按钮紧挨「拍摄标准照」，两条渠道结果口径一致。
        final Button aiTodoReferenceGalleryButton = new Button(this);
        aiTodoReferenceGalleryButton.setText(PhotoChannel.GALLERY.label());
        aiTodoBox.addView(aiTodoReferenceButton);
        aiTodoBox.addView(aiTodoReferenceGalleryButton);
        aiTodoBox.addView(aiTodoFeatureInput);
        TextView aiTodoNotice = new TextView(this);
        aiTodoNotice.setTextSize(12);
        aiTodoNotice.setTextColor(getColor(R.color.textSecondary));
        aiTodoNotice.setText("启用后每次完成需拍照：照片与特征描述会上传到你配置的模型服务商判定；"
                + "未配 key 或网络异常时按本地预检完成并标记「待复核」。");
        aiTodoBox.addView(aiTodoNotice);
        int insertAt = editorBox.indexOfChild(errorText);
        editorBox.addView(aiTodoCheck, Math.max(0, insertAt));
        editorBox.addView(aiTodoBox, Math.max(0, insertAt) + 1);
        aiTodoCheck.setOnCheckedChangeListener((buttonView, isChecked) ->
                aiTodoBox.setVisibility(isChecked ? View.VISIBLE : View.GONE));
        aiTodoReferenceButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (pendingReferenceFile != null || pendingGalleryPick) {
                    Toast.makeText(MainActivity.this, "上一次拍照还在进行中", Toast.LENGTH_SHORT).show();
                    return;
                }
                File reference = AiPhotoStore.newReferenceFile(MainActivity.this);
                Intent capture = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
                capture.putExtra(MediaStore.EXTRA_OUTPUT,
                        AiPhotoProvider.uriFor(MainActivity.this, reference));
                capture.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        | Intent.FLAG_GRANT_READ_URI_PERMISSION);
                try {
                    pendingReferenceForTodo = true;
                    pendingReferenceFile = reference;
                    startActivityForResult(capture, REQUEST_AI_TODO_REFERENCE_PHOTO);
                } catch (Exception exception) {
                    pendingReferenceFile = null;
                    Toast.makeText(MainActivity.this, "无法启动系统相机：" + exception.getMessage(),
                            Toast.LENGTH_LONG).show();
                }
            }
        });
        aiTodoReferenceGalleryButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startAiGalleryReference(true);
            }
        });

        final Calendar start = Calendar.getInstance();
        final Calendar end = Calendar.getInstance();
        if (todo == null) {
            end.add(Calendar.HOUR_OF_DAY, 1);
            durationHours.setText("1");
            durationMinutes.setText("0");
        } else {
            start.setTimeInMillis(todo.startTime);
            end.setTimeInMillis(todo.endTime);
            titleInput.setText(todo.title);
            durationHours.setText(String.valueOf(todo.durationMinutes / 60));
            durationMinutes.setText(String.valueOf(todo.durationMinutes % 60));
        }
        Runnable updateLabels = new Runnable() {
            @Override
            public void run() {
                startDateButton.setText("开始日期：" + formatDate(start));
                startTimeButton.setText("开始时刻：" + formatTime(start));
                endDateButton.setText("结束日期：" + formatDate(end));
                endTimeButton.setText("结束时刻：" + formatTime(end));
            }
        };
        updateLabels.run();

        startDateButton.setOnClickListener(v -> showDateDialog("开始日期", start, updateLabels));
        startTimeButton.setOnClickListener(v -> showTimeDialog("开始时刻", start, updateLabels));
        endDateButton.setOnClickListener(v -> showDateDialog("结束日期", end, updateLabels));
        endTimeButton.setOnClickListener(v -> showTimeDialog("结束时刻", end, updateLabels));

        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle(todo == null ? "新建待办" : "编辑待办")
                .setView(view)
                .setNegativeButton("取消", null)
                .setPositiveButton("保存", null);
        final AlertDialog dialog = builder.create();
        dialog.show();
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                int hours = parseInt(durationHours.getText().toString(), 0);
                int minutes = parseInt(durationMinutes.getText().toString(), 0);
                int duration = hours * 60 + minutes;
                List<String> errors = TodoLogic.validateTodo(titleInput.getText().toString(),
                        start.getTimeInMillis(), end.getTimeInMillis(), duration);
                if (!errors.isEmpty()) {
                    errorText.setText(String.join("；", errors));
                    return;
                }
                repository.saveTodo(todo == null ? null : todo.id, titleInput.getText().toString(),
                        start.getTimeInMillis(), end.getTimeInMillis(), duration,
                        todo != null && todo.completed,
                        aiTodoCheck.isChecked() ? VerifyMode.AI_PHOTO : VerifyMode.NONE,
                        aiTodoFeatureInput.getText().toString().trim(),
                        editorTodoReferencePhoto);
                dialog.dismiss();
                render();
            }
        });
    }

    private void showDateDialog(String title, Calendar target, Runnable updateLabels) {
        new DatePickerDialog(this, (view, year, month, dayOfMonth) -> {
            target.set(Calendar.YEAR, year);
            target.set(Calendar.MONTH, month);
            target.set(Calendar.DAY_OF_MONTH, dayOfMonth);
            target.set(Calendar.SECOND, 0);
            target.set(Calendar.MILLISECOND, 0);
            updateLabels.run();
        }, target.get(Calendar.YEAR), target.get(Calendar.MONTH), target.get(Calendar.DAY_OF_MONTH)).show();
    }

    private void showTimeDialog(String title, Calendar target, Runnable updateLabels) {
        TimePickerDialog dialog = new TimePickerDialog(this, (view, hourOfDay, minute) -> {
            target.set(Calendar.HOUR_OF_DAY, hourOfDay);
            target.set(Calendar.MINUTE, minute);
            target.set(Calendar.SECOND, 0);
            target.set(Calendar.MILLISECOND, 0);
            updateLabels.run();
        }, target.get(Calendar.HOUR_OF_DAY), target.get(Calendar.MINUTE), true);
        dialog.setTitle(title);
        dialog.show();
    }

    // ------------------------------------------------------------------
    // 问题 2：打卡日历 + 请假
    // ------------------------------------------------------------------

    private void showTaskCalendar(final Task task) {
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_task_calendar, null, false);
        TextView nameView = view.findViewById(R.id.calendar_task_name);
        TextView rangeView = view.findViewById(R.id.calendar_task_range);
        TextView monthLabel = view.findViewById(R.id.calendar_month_label);
        TextView streakView = view.findViewById(R.id.calendar_streak);
        Button prev = view.findViewById(R.id.calendar_prev_month);
        Button next = view.findViewById(R.id.calendar_next_month);
        GridLayout grid = view.findViewById(R.id.calendar_grid);
        // 每次打开日历时清空单元格缓存：同一对话框内翻月复用，跨对话框重建。
        calendarCells.clear();

        nameView.setText(task.name);
        rangeView.setText(task.startTime + " - " + task.endTime);

        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(view)
                .setNegativeButton("关闭", null)
                .create();

        final Calendar month = Calendar.getInstance();
        month.set(Calendar.DAY_OF_MONTH, 1);
        month.set(Calendar.HOUR_OF_DAY, 0);
        month.set(Calendar.MINUTE, 0);
        month.set(Calendar.SECOND, 0);
        month.set(Calendar.MILLISECOND, 0);

        final Runnable renderMonth = new Runnable() {
            @Override
            public void run() {
                renderCalendarGrid(task, month, grid, this);
                monthLabel.setText(new SimpleDateFormat("yyyy年M月", Locale.CHINA).format(month.getTime()));
                streakView.setText("当前连续 "
                        + TaskLogic.calculateStreak(task, repository.getCompletedDates(task.id), new Date())
                        + " 天（请假会打断）");
            }
        };
        dialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(DialogInterface d) {
                render();
            }
        });

        prev.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                month.add(Calendar.MONTH, -1);
                renderMonth.run();
            }
        });
        next.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                month.add(Calendar.MONTH, 1);
                renderMonth.run();
            }
        });

        dialog.show();
        renderMonth.run();
    }

    /**
     * 渲染某月的 7 列网格（周一为第一列，最后补齐整行）。
     * 42 个 TextView 只在首次渲染时创建并绑定一次点击监听，之后翻月、请假变更
     * 均复用同一批视图，只更新文本/背景/颜色与 tag 中的日期，避免重复构建视图。
     *
     * @param afterChange 请假变更后需要执行的刷新动作（重绘当前月 + 刷新主列表）
     */
    private void renderCalendarGrid(final Task task, final Calendar month, GridLayout grid,
                                    final Runnable afterChange) {
        Date now = new Date();
        String todayKey = TaskLogic.dateKey(now);

        if (calendarCells.isEmpty()) {
            for (int i = 0; i < 42; i++) {
                TextView cell = new TextView(this);
                GridLayout.LayoutParams params = new GridLayout.LayoutParams();
                params.width = 0;
                params.height = ViewGroup.LayoutParams.WRAP_CONTENT;
                params.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
                params.setMargins(2, 2, 2, 2);
                cell.setLayoutParams(params);
                cell.setGravity(Gravity.CENTER);
                cell.setPadding(0, 12, 0, 12);
                cell.setTextSize(13);
                cell.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        Date day = (Date) v.getTag();
                        if (day != null) showDayActions(task, day, afterChange);
                    }
                });
                grid.addView(cell);
                calendarCells.add(cell);
            }
        }

        Calendar cursor = (Calendar) month.clone();
        int leading = (cursor.get(Calendar.DAY_OF_WEEK) + 5) % 7;
        cursor.add(Calendar.DAY_OF_MONTH, -leading);

        for (int i = 0; i < 42; i++) {
            final Calendar day = (Calendar) cursor.clone();
            final String key = TaskLogic.dateKey(day.getTime());
            // 同时比较年与月，避免跨年时把上一年/下一年的同月天数误判为本月。
            boolean sameMonth = day.get(Calendar.YEAR) == month.get(Calendar.YEAR)
                    && day.get(Calendar.MONTH) == month.get(Calendar.MONTH);
            TaskLogic.CalendarDayState state = TaskLogic.calendarDayState(task, repository.getCompletedDates(task.id),
                    day.getTime(), now);

            TextView cell = calendarCells.get(i);
            cell.setTag(day.getTime());
            cell.setText(String.valueOf(day.get(Calendar.DAY_OF_MONTH)));
            cell.setBackgroundResource(backgroundFor(state));
            cell.setTextColor(textColorFor(state));
            cell.setTypeface(TaskLogic.isScheduled(task, day.getTime()) && sameMonth
                    ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            // 不属于本月的日期整体淡显；同时非重复日已用淡色底区分。
            cell.setAlpha(sameMonth ? 1f : 0.45f);
            if (key.equals(todayKey)) cell.setTypeface(Typeface.DEFAULT_BOLD);
            cursor.add(Calendar.DAY_OF_MONTH, 1);
        }
    }

    private static int backgroundFor(TaskLogic.CalendarDayState state) {
        switch (state) {
            case DONE: return R.drawable.calendar_day_done;
            case MISSED: return R.drawable.calendar_day_missed;
            case LEAVE: return R.drawable.calendar_day_leave;
            case TODAY:
            case TODAY_ACTIVE: return R.drawable.calendar_day_today;
            case OFF:
            case FUTURE: return R.drawable.calendar_day_off;
            default: return R.drawable.calendar_day_normal;
        }
    }

    private int textColorFor(TaskLogic.CalendarDayState state) {
        switch (state) {
            case DONE: return getColor(R.color.calendarDoneText);
            case LEAVE: return getColor(R.color.calendarLeaveText);
            case MISSED: return getColor(R.color.calendarTextMissed);
            case OFF:
            case FUTURE: return getColor(R.color.calendarTextOff);
            default: return getColor(R.color.textPrimary);
        }
    }

    private void showDayActions(final Task task, final Date day, final Runnable afterChange) {
        final String key = TaskLogic.dateKey(day);
        final boolean leave = task.leaveDates.contains(key);
        final boolean isToday = key.equals(TaskLogic.dateKey(new Date()));
        final String title = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(day) + (isToday ? "（今天）" : "");

        List<String> actions = new ArrayList<>();
        // 今天与未来的重复日提供「设为请假」（未来为预请假）；过去日期不提供。
        if (TaskLogic.canLeave(task, day, new Date())) {
            actions.add("设为请假");
        }
        if (leave) {
            actions.add("取消请假");
        }
        final int cancelIndex = actions.size();
        actions.add("取消");

        final String[] items = actions.toArray(new String[0]);
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        if (which == cancelIndex) return; // 「取消」分支直接关闭
                        boolean wantLeave = "设为请假".equals(items[which]);
                        if (wantLeave && !TaskLogic.canLeave(task, day, new Date())) {
                            showLeaveRejected();
                            return;
                        }
                        applyLeaveChange(task, key, wantLeave, afterChange);
                    }
                })
                .show();
    }

    private void applyLeaveChange(final Task task, String key, boolean leave, Runnable afterChange) {
        if (!repository.setLeave(task.id, key, leave)) return;
        repository.reload();
        // 主列表先刷新（连续天数会随请假变化），再按需重绘日历当前月份。
        render();
        if (afterChange != null) afterChange.run();
    }

    private void showLeaveRejected() {
        new AlertDialog.Builder(this)
                .setTitle("无法请假")
                    .setMessage("请假仅支持今天或未来的重复日：过去日期不能补请假；已请假日期可随时取消。")
                .setPositiveButton("知道了", null)
                .show();
    }

    private void confirmDeleteTask(final Task task) {
        new AlertDialog.Builder(this)
                .setTitle("删除任务")
                .setMessage("删除「" + task.name + "」及其全部打卡记录？")
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        repository.deleteTask(task.id);
                        render();
                    }
                })
                .show();
    }

    private void confirmDeleteTodo(final Todo todo) {
        new AlertDialog.Builder(this)
                .setTitle("删除待办")
                .setMessage("删除「" + todo.title + "」？")
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        repository.deleteTodo(todo.id);
                        render();
                    }
                })
                .show();
    }

    private String formatDate(Calendar calendar) {
        return new SimpleDateFormat("M月d日", Locale.CHINA).format(calendar.getTime());
    }

    private String formatTime(Calendar calendar) {
        return new SimpleDateFormat("HH:mm", Locale.CHINA).format(calendar.getTime());
    }

    private String formatDateTime(long milliseconds) {
        return new SimpleDateFormat("M月d日 HH:mm", Locale.CHINA).format(new Date(milliseconds));
    }

    private static int parseInt(String value, int fallback) {
        if (value == null || value.trim().isEmpty()) return fallback;
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static String statusLabel(TaskStatus status) {
        switch (status) {
            case OFF: return "今日不重复";
            case UPCOMING: return "未开始";
            case ACTIVE: return "可打卡";
            case DONE: return "已完成";
            case MISSED: return "已错过";
            case LEAVE: return "已请假";
            default: return "";
        }
    }

    private static String todoStatusLabel(TodoStatus status) {
        switch (status) {
            case NOT_STARTED: return "未开始";
            case IN_PROGRESS: return "进行中";
            case EXPIRED: return "已过期";
            case COMPLETED: return "已完成";
            default: return "";
        }
    }

    private static int todoStatusBackground(TodoStatus status) {
        switch (status) {
            case IN_PROGRESS: return R.drawable.status_active;
            case EXPIRED: return R.drawable.status_missed;
            case COMPLETED: return R.drawable.status_done;
            default: return R.drawable.status_upcoming;
        }
    }

    private int todoStatusStatusTextColor(TodoStatus status) {
        switch (status) {
            case IN_PROGRESS: return getColor(R.color.statusActiveText);
            case EXPIRED: return getColor(R.color.statusMissedText);
            case COMPLETED: return getColor(R.color.statusDoneText);
            default: return getColor(R.color.statusUpcomingText);
        }
    }

    private static int statusBackground(TaskStatus status) {
        switch (status) {
            case ACTIVE: return R.drawable.status_active;
            case DONE: return R.drawable.status_done;
            case MISSED: return R.drawable.status_missed;
            case LEAVE: return R.drawable.status_leave;
            default: return R.drawable.status_upcoming;
        }
    }

    private int statusTextColor(TaskStatus status) {
        switch (status) {
            case ACTIVE: return getColor(R.color.statusActiveText);
            case DONE: return getColor(R.color.statusDoneText);
            case MISSED: return getColor(R.color.statusMissedText);
            case LEAVE: return getColor(R.color.statusLeaveText);
            default: return getColor(R.color.statusUpcomingText);
        }
    }

    private static int dp(int value) {
        return (int) (value * android.content.res.Resources.getSystem().getDisplayMetrics().density);
    }
}
