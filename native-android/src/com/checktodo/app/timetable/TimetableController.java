package com.checktodo.app.timetable;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.app.Dialog;
import android.app.TimePickerDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.ScrollView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import android.webkit.CookieManager;
import android.webkit.ValueCallback;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import com.checktodo.app.R;
import com.checktodo.app.data.TimetableRepository;
import com.checktodo.app.domain.CourseBase;
import com.checktodo.app.domain.CourseDetail;
import com.checktodo.app.domain.HtmlTimetableParser;
import com.checktodo.app.domain.GeometryGridSolver;
import com.checktodo.app.domain.OnlineImportLogic;
import com.checktodo.app.domain.OneShotGate;
import com.checktodo.app.domain.OnlineDivParser;
import com.checktodo.app.domain.OnlineExtraction;
import com.checktodo.app.domain.PassiveCapture;
import com.checktodo.app.domain.ExtractionState;
import com.checktodo.app.domain.FrameDump;
import com.checktodo.app.domain.ScauGridParser;
import com.checktodo.app.domain.DedicatedParserPlugin;
import com.checktodo.app.domain.TimetableParserRegistry;
import com.checktodo.app.domain.TimeNode;
import com.checktodo.app.domain.TimeTableSettings;
import com.checktodo.app.domain.TimeTableDraft;
import com.checktodo.app.domain.TimetableConfig;
import com.checktodo.app.domain.TimetableCsvParser;
import com.checktodo.app.domain.TimetableLogic;
import com.checktodo.app.domain.TimetableSheet;
import com.checktodo.app.wakeup_parser.SchoolInfo;
import com.checktodo.app.wakeup_parser.SchoolInfoDecoder;
import com.checktodo.app.wakeup_parser.WakeupCourseMapper;
import com.checktodo.app.wakeup_parser.WakeupParser;
import com.checktodo.app.wakeup_parser.WakeupParserFactory;
import com.checktodo.app.wakeup_parser.WakeupParserResult;

import java.io.ByteArrayOutputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.zip.GZIPInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/** 课表标签页：周视图、课程编辑、设置与 CSV 导入。 */
public final class TimetableController {
    public static final int REQUEST_CSV = 3001;
    public static final int REQUEST_HTML = 3002;
    private static final String[] DAY_LABELS = {"周一", "周二", "周三", "周四", "周五", "周六", "周日"};
    private static final String[] TYPE_LABELS = {"连续周", "单周", "双周"};

    private final Activity activity;
    private final TimetableRepository repository;
    private final LayoutInflater inflater;
    private View root;
    private TimetableView grid;
    private LinearLayout dayHeader;
    private TextView weekLabel;
    private TextView hint;
    private TextView timetableTitle;
    private int selectedWeek = 1;
    private boolean selectedWeekExplicit = false;
    private Dialog onlineImportDialog;
    private int onlineLastCourseCount = -1;
    private int onlineLastDetailCount = -1;
    private final List<CourseBase> onlineBases = new ArrayList<>();
    private final List<CourseDetail> onlineDetails = new ArrayList<>();
    private final List<String> onlineWarnings = new ArrayList<>();
    private String onlineDiagnostics = "尚未扫描";
    private final AtomicBoolean onlineParsing = new AtomicBoolean(false);
    /**
     * frame 列表的轮次号：每轮「发现/探测」递增一次。异步回调返回时若轮次已变
     * （用户点了「刷新列表」），旧回调必须整体作废——不得把旧结果写进新列表、
     * 也不得用旧状态文案覆盖新一轮提示。
     */
    private final AtomicInteger frameDiscoveryGeneration = new AtomicInteger(0);
    private final OnlineImportLogic.ResourceRefreshState onlineResourceState =
            new OnlineImportLogic.ResourceRefreshState();

    /** 分块接收器在 window 上的对象名，JS 侧与 Java 侧必须一致。 */
    static final String SINK_NAME = "__checkTodoSink";
    /**
     * 被动捕获池：页面浏览期间由 {@link PassiveCaptureClient} 持续写入，
     * 点「提取课表」时作为第一优先级数据源（覆盖 POST 型教务系统）。
     */
    private final PassiveCapture.CapturePool onlineCapturePool = new PassiveCapture.CapturePool();
    /** 当前对话框的被动捕获客户端，dismiss 时关闭其排空线程。 */
    private volatile PassiveCaptureClient onlineCaptureClient;
    /** 提取状态机：IDLE/RUNNING/STOPPED/COMPLETED/WATCHDOG_ABORTED。 */
    private final ExtractionState onlineExtractionState = new ExtractionState();
    /** 本轮提取的上下文（后台线程 / 分块拼装 / 诊断 / 硬超时）；null 表示当前没有在提取。 */
    private volatile OnlineExtractionSession activeSession;
    /** 硬超时看门狗线程：即使后台线程被原生调用卡死，也保证按钮恢复。 */
    private volatile Thread watchdog;
    /** 下一次点「提取课表」是否附带深度扫描（长按设置）。 */
    private boolean deepScanRequested;
    /** 供后台线程投递 JS 用的 WebView 引用（仅在 runOnUi 内被访问）。 */
    private volatile WebView currentWebView;
    /** 单次 XHR 请求的连接/读取超时上限（毫秒）。 */
    private static final int ONLINE_FETCH_TIMEOUT_MS = 10_000;
    private final List<SchoolInfo> schoolCatalog = new ArrayList<>();
    private SchoolInfo selectedSchool;

    public TimetableController(Activity activity, TimetableRepository repository) {
        this.activity = activity;
        this.repository = repository;
        this.inflater = LayoutInflater.from(activity);
    }

    public void show(ViewGroup container) {
        container.removeAllViews();
        if (root == null) {
            root = inflater.inflate(R.layout.timetable_panel, container, false);
            grid = root.findViewById(R.id.timetable_grid);
            dayHeader = root.findViewById(R.id.timetable_day_header);
            weekLabel = root.findViewById(R.id.timetable_week);
            hint = root.findViewById(R.id.timetable_hint);
            timetableTitle = root.findViewById(R.id.timetable_title);
            Button prev = root.findViewById(R.id.button_week_prev);
            Button next = root.findViewById(R.id.button_week_next);
            Button current = root.findViewById(R.id.button_week_current);
            Button settings = root.findViewById(R.id.button_timetable_settings);
            Button importCsv = root.findViewById(R.id.button_import_csv);
            Button importHtml = root.findViewById(R.id.button_import_html);
            Button importOnline = root.findViewById(R.id.button_import_online);

            timetableTitle.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    showTimetableManager();
                }
            });

            prev.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    if (selectedWeek > 1) {
                        selectedWeek--;
                        selectedWeekExplicit = true;
                        refresh();
                    }
                }
            });
            next.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    if (selectedWeek < repository.getConfig().maxWeek) {
                        selectedWeek++;
                        selectedWeekExplicit = true;
                        refresh();
                    }
                }
            });
            current.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    selectedWeek = TimetableLogic.currentWeek(repository.getConfig(), new Date());
                    selectedWeekExplicit = true;
                    refresh();
                }
            });
            settings.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    showSettings();
                }
            });
            importCsv.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    openCsvPicker();
                }
            });
            importHtml.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    openHtmlPicker();
                }
            });
            importOnline.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    showOnlineImport();
                }
            });
            grid.setCourseClickListener(new TimetableView.CourseClickListener() {
                @Override
                public void onCourseClick(CourseDetail detail) {
                    showCourseDetail(detail);
                }
            });
        }
        container.addView(root);
        if (!selectedWeekExplicit) {
            selectedWeek = TimetableLogic.currentWeek(repository.getConfig(), new Date());
        }
        refresh();
    }

    public void refresh() {
        TimetableConfig config = repository.getConfig();
        int currentWeek = TimetableLogic.currentWeek(config, new Date());
        selectedWeek = Math.max(1, Math.min(config.maxWeek, selectedWeek));
        TimetableSheet currentSheet = repository.getCurrentTimetable();
        timetableTitle.setText(currentSheet.name);
        weekLabel.setText("第 " + selectedWeek + " 周");
        renderDayHeader(config, currentWeek);
        grid.setTimetable(config, repository.getBases(), repository.getDetails(),
                repository.getTimeNodes(), selectedWeek, new Date());
        List<String> hints = new ArrayList<>();
        if (TimetableLogic.isBeforeTermStart(config, new Date())) hints.add("尚未开学，当前按第 1 周展示");
        if (currentWeek >= config.maxWeek) hints.add("已到学期最后一周");
        hints.add("学期开始：" + config.startDate + "；共 " + config.maxWeek + " 周；每天 " + config.nodes + " 节");
        hint.setText(TextUtils.join("；", hints));
    }

    private void showTimetableManager() {
        final List<TimetableSheet> sheets = repository.getTimetables();
        final List<String> labels = new ArrayList<>();
        for (TimetableSheet sheet : sheets) {
            labels.add(sheet.id.equals(repository.getCurrentTimetable().id)
                    ? sheet.name + "（当前）" : sheet.name);
        }

        LinearLayout panel = new LinearLayout(activity);
        panel.setOrientation(LinearLayout.VERTICAL);
        int padding = dp(12);
        panel.setPadding(padding, padding, padding, 0);
        ListView list = new ListView(activity);
        list.setAdapter(new ArrayAdapter<>(activity, android.R.layout.simple_list_item_1, labels));
        list.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                dp(320)));
        panel.addView(list);

        LinearLayout actions = new LinearLayout(activity);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        Button create = new Button(activity, null, android.R.attr.buttonBarButtonStyle);
        Button rename = new Button(activity, null, android.R.attr.buttonBarButtonStyle);
        Button delete = new Button(activity, null, android.R.attr.buttonBarButtonStyle);
        create.setText("新建课表");
        rename.setText("重命名");
        delete.setText("删除");
        LinearLayout.LayoutParams actionParams = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        actions.addView(create, actionParams);
        actions.addView(rename, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        actions.addView(delete, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        panel.addView(actions, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        list.setOnItemClickListener(new android.widget.AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(android.widget.AdapterView<?> parent, View view,
                                    int position, long id) {
                if (position < 0 || position >= sheets.size()) return;
                TimetableSheet target = sheets.get(position);
                if (target.id.equals(repository.getCurrentTimetable().id)) {
                    Toast.makeText(activity, "这已是当前课表", Toast.LENGTH_SHORT).show();
                    return;
                }
                repository.switchTimetable(target.id);
                selectedWeek = TimetableLogic.currentWeek(repository.getConfig(), new Date());
                refresh();
                Toast.makeText(activity, "已切换到 " + target.name, Toast.LENGTH_SHORT).show();
            }
        });
        create.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                showCreateTimetable();
            }
        });
        rename.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                showRenameTimetable(sheets);
            }
        });
        delete.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                showDeleteTimetable(sheets);
            }
        });

        new AlertDialog.Builder(activity)
                .setTitle("课表管理")
                .setView(panel)
                .setNegativeButton("关闭", null)
                .show();
    }

    private void showCreateTimetable() {
        LinearLayout panel = new LinearLayout(activity);
        panel.setOrientation(LinearLayout.VERTICAL);
        int padding = dp(12);
        panel.setPadding(padding, padding, padding, 0);
        final EditText input = new EditText(activity);
        input.setHint("课表名称");
        input.setSingleLine(true);
        panel.addView(input, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        final CheckBox copyCourses = new CheckBox(activity);
        copyCourses.setText("复制当前课表课程");
        panel.addView(copyCourses);

        new AlertDialog.Builder(activity)
                .setTitle("新建课表")
                .setView(panel)
                .setNegativeButton("取消", null)
                .setPositiveButton("创建", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        String name = input.getText().toString().trim();
                        if (name.isEmpty()) {
                            Toast.makeText(activity, "请输入课表名称", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        repository.createTimetable(name, copyCourses.isChecked());
                        selectedWeek = TimetableLogic.currentWeek(repository.getConfig(), new Date());
                        refresh();
                    }
                })
                .show();
    }

    private void showRenameTimetable(final List<TimetableSheet> sheets) {
        final List<String> names = new ArrayList<>();
        for (TimetableSheet sheet : sheets) names.add(sheet.name);
        final int[] target = {-1};
        new AlertDialog.Builder(activity)
                .setTitle("选择要重命名的课表")
                .setSingleChoiceItems(names.toArray(new String[0]),
                        sheets.indexOf(repository.getCurrentTimetable()),
                        new android.content.DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(android.content.DialogInterface dialog, int which) {
                                target[0] = which;
                            }
                        })
                .setNegativeButton("取消", null)
                .setPositiveButton("下一步", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        int selected = target[0] < 0
                                ? sheets.indexOf(repository.getCurrentTimetable()) : target[0];
                        if (selected < 0 || selected >= sheets.size()) return;
                        showRenameInput(sheets.get(selected));
                    }
                })
                .show();
    }

    private void showRenameInput(final TimetableSheet sheet) {
        final EditText input = new EditText(activity);
        input.setText(sheet.name);
        input.setSingleLine(true);
        int padding = dp(12);
        input.setPadding(padding, padding, padding, 0);
        new AlertDialog.Builder(activity)
                .setTitle("重命名课表")
                .setView(input)
                .setNegativeButton("取消", null)
                .setPositiveButton("保存", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        String name = input.getText().toString().trim();
                        if (name.isEmpty()) {
                            Toast.makeText(activity, "名称不能为空", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        repository.renameTimetable(sheet.id, name);
                        refresh();
                    }
                })
                .show();
    }

    private void showDeleteTimetable(final List<TimetableSheet> sheets) {
        final List<TimetableSheet> deletable = new ArrayList<>();
        for (TimetableSheet sheet : sheets) {
            if (!sheet.id.equals(repository.getCurrentTimetable().id)) deletable.add(sheet);
        }
        if (deletable.isEmpty()) {
            Toast.makeText(activity, "至少保留一张课表，且当前课表不能删除", Toast.LENGTH_SHORT).show();
            return;
        }
        final List<String> names = new ArrayList<>();
        for (TimetableSheet sheet : deletable) names.add(sheet.name);
        final int[] target = {0};
        new AlertDialog.Builder(activity)
                .setTitle("选择要删除的课表")
                .setSingleChoiceItems(names.toArray(new String[0]), 0,
                        new android.content.DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(android.content.DialogInterface dialog, int which) {
                                target[0] = which;
                            }
                        })
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        int selected = target[0];
                        if (selected < 0 || selected >= deletable.size()) return;
                        final TimetableSheet sheet = deletable.get(selected);
                        new AlertDialog.Builder(activity)
                                .setTitle("删除课表")
                                .setMessage("删除「" + sheet.name + "」及其课程、时间表和配置？")
                                .setNegativeButton("取消", null)
                                .setPositiveButton("删除", new android.content.DialogInterface.OnClickListener() {
                                    @Override
                                    public void onClick(android.content.DialogInterface dialog, int which) {
                                        repository.deleteTimetable(sheet.id);
                                        refresh();
                                    }
                                })
                                .show();
                    }
                })
                .show();
    }

    private void renderDayHeader(TimetableConfig config, int currentWeek) {
        dayHeader.removeAllViews();
        View spacer = new View(activity);
        LinearLayout.LayoutParams spacerParams = new LinearLayout.LayoutParams(dp(46),
                ViewGroup.LayoutParams.MATCH_PARENT);
        dayHeader.addView(spacer, spacerParams);

        int today = TimetableLogic.dayOfWeek(new Date());
        int columns = columns(config);
        for (int day = 1; day <= columns; day++) {
            TextView label = new TextView(activity);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.MATCH_PARENT, 1f);
            label.setLayoutParams(params);
            label.setText(DAY_LABELS[day - 1]);
            label.setGravity(android.view.Gravity.CENTER);
            label.setTextSize(12);
            if (selectedWeek == currentWeek && day == today) {
                label.setTextColor(activity.getColor(R.color.statusActiveText));
                label.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            } else {
                label.setTextColor(activity.getColor(R.color.textSecondary));
            }
            dayHeader.addView(label, params);
        }
    }

    private void showCourseDetail(final CourseDetail detail) {
        CourseBase base = repository.findBase(detail.baseId);
        if (base == null) return;
        TimeNode start = repository.findTimeNode(detail.startNode);
        TimeNode end = repository.findTimeNode(detail.endNode());
        String timeRange = start != null && end != null ? start.startTime + " - " + end.endTime : "未设置时间";
        String message = "教师：" + (detail.teacher.isEmpty() ? "-" : detail.teacher) + "\n"
                + "教室：" + (detail.room.isEmpty() ? "-" : detail.room) + "\n"
                + "星期：" + DAY_LABELS[detail.day - 1] + "\n"
                + "节次：" + detail.startNode + " - " + detail.endNode() + "（共 " + detail.step + " 节）\n"
                + "时间：" + timeRange + "\n"
                + "周次：" + detail.startWeek + " - " + detail.endWeek + "（" + typeLabel(detail.type) + "）\n"
                + "学分：" + (base.credit <= 0 ? "-" : String.valueOf(base.credit)) + "\n"
                + "备注：" + (base.note.isEmpty() ? "-" : base.note);

        new AlertDialog.Builder(activity)
                .setTitle(base.name)
                .setMessage(message)
                .setNegativeButton("编辑", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        showCourseEditor(detail);
                    }
                })
                .setNeutralButton("删除", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        confirmDelete(detail);
                    }
                })
                .setPositiveButton("关闭", null)
                .show();
    }

    private void confirmDelete(final CourseDetail detail) {
        CourseBase base = repository.findBase(detail.baseId);
        String name = base == null ? "课程" : base.name;
        new AlertDialog.Builder(activity)
                .setTitle("删除课程")
                .setMessage("删除「" + name + "」及其全部排课？")
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        repository.deleteCourse(detail.baseId);
                        refresh();
                    }
                })
                .show();
    }

    public void showCourseEditor(final CourseDetail detail) {
        View view = inflater.inflate(R.layout.dialog_course_editor, null, false);
        final EditText name = view.findViewById(R.id.input_course_name);
        final EditText teacher = view.findViewById(R.id.input_course_teacher);
        final EditText room = view.findViewById(R.id.input_course_room);
        final Spinner daySpinner = view.findViewById(R.id.spinner_course_day);
        final Spinner typeSpinner = view.findViewById(R.id.spinner_course_type);
        final EditText startNode = view.findViewById(R.id.input_course_start_node);
        final EditText endNode = view.findViewById(R.id.input_course_end_node);
        final EditText startWeek = view.findViewById(R.id.input_course_start_week);
        final EditText endWeek = view.findViewById(R.id.input_course_end_week);
        final EditText note = view.findViewById(R.id.input_course_note);
        final EditText credit = view.findViewById(R.id.input_course_credit);
        final TextView error = view.findViewById(R.id.course_error_text);

        daySpinner.setAdapter(new ArrayAdapter<>(activity, android.R.layout.simple_spinner_dropdown_item, DAY_LABELS));
        typeSpinner.setAdapter(new ArrayAdapter<>(activity, android.R.layout.simple_spinner_dropdown_item, TYPE_LABELS));

        if (detail == null) {
            daySpinner.setSelection(0);
            startNode.setText("1");
            endNode.setText("2");
            startWeek.setText("1");
            endWeek.setText(String.valueOf(repository.getConfig().maxWeek));
        } else {
            CourseBase base = repository.findBase(detail.baseId);
            if (base != null) name.setText(base.name);
            teacher.setText(detail.teacher);
            room.setText(detail.room);
            daySpinner.setSelection(detail.day - 1);
            startNode.setText(String.valueOf(detail.startNode));
            endNode.setText(String.valueOf(detail.endNode()));
            startWeek.setText(String.valueOf(detail.startWeek));
            endWeek.setText(String.valueOf(detail.endWeek));
            typeSpinner.setSelection(detail.type);
            if (base != null) note.setText(base.note);
            if (base != null && base.credit > 0) credit.setText(String.valueOf(base.credit));
        }

        final AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle(detail == null ? "新建课程" : "编辑课程")
                .setView(view)
                .setNegativeButton("取消", null)
                .setPositiveButton("保存", null)
                .create();
        dialog.show();
        dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                String courseName = name.getText().toString().trim();
                int day = daySpinner.getSelectedItemPosition() + 1;
                int start = parseInt(startNode.getText().toString(), 0);
                int end = parseInt(endNode.getText().toString(), 0);
                int weekStart = parseInt(startWeek.getText().toString(), 0);
                int weekEnd = parseInt(endWeek.getText().toString(), 0);
                int type = typeSpinner.getSelectedItemPosition();
                double creditValue = parseDouble(credit.getText().toString(), 0);
                TimetableConfig config = repository.getConfig();

                if (courseName.isEmpty()) {
                    error.setText("请填写课程名称");
                    return;
                }
                if (start < 1 || end < start || end > config.nodes) {
                    error.setText("节次必须在 1 到 " + config.nodes + " 之间，且结束不早于开始");
                    return;
                }
                if (weekStart < 1 || weekEnd < weekStart || weekEnd > config.maxWeek) {
                    error.setText("周次必须在 1 到 " + config.maxWeek + " 之间");
                    return;
                }

                CourseBase existing = detail == null ? null : repository.findBase(detail.baseId);
                CourseBase base = detail == null || existing == null
                        ? new CourseBase("", courseName, 0, note.getText().toString(), creditValue)
                        : new CourseBase(existing.id, courseName, existing.color,
                                note.getText().toString(), creditValue);
                CourseDetail next = new CourseDetail(detail == null ? "" : detail.id,
                        detail == null ? "" : detail.baseId, day, start, end - start + 1,
                        weekStart, weekEnd, type, room.getText().toString(), teacher.getText().toString(),
                        detail == null ? 0 : detail.level);
                repository.saveCourse(base, next);
                refresh();
            }
        });
    }

    private void showSettings() {
        View view = inflater.inflate(R.layout.dialog_timetable_settings, null, false);
        final Button startButton = view.findViewById(R.id.button_term_start);
        final EditText maxWeek = view.findViewById(R.id.input_max_week);
        final EditText nodes = view.findViewById(R.id.input_daily_nodes);
        final CheckBox showSat = view.findViewById(R.id.check_show_sat);
        final CheckBox showSun = view.findViewById(R.id.check_show_sun);
        final CheckBox showOther = view.findViewById(R.id.check_show_other_week);
        final TextView error = view.findViewById(R.id.timetable_settings_error);
        final Button timeTableButton = view.findViewById(R.id.button_time_table);
        TimetableConfig config = repository.getConfig();

        // 节数与时间表由「单一数据源」TimeTableDraft 统一持有：输入框与编辑弹窗都只改它，
        // 显示永远从它读回，从结构上杜绝「两处数值脱节」。
        final TimeTableDraft draft = TimeTableDraft.of(repository.getTimeNodes(), config.nodes);
        final Runnable updateTimeTableButton = new Runnable() {
            @Override
            public void run() {
                timeTableButton.setText("时间表设置：" + draft.nodeCount() + " 节，点击编辑");
            }
        };
        updateTimeTableButton.run();
        // 先注册监听：时间表弹窗里增删节次后回填输入框时，需要临时摘掉监听避免递归。
        final android.text.TextWatcher nodesWatcher = new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(android.text.Editable editable) {
                int requested = parseInt(editable.toString(), 0);
                if (requested < 1 || requested > TimeTableDraft.MAX_NODES) return;
                // 输入框是「写入口」之一：改节数即裁剪/扩充时间表草稿。
                if (draft.setNodeCount(requested)) {
                    updateTimeTableButton.run();
                }
            }
        };
        nodes.addTextChangedListener(nodesWatcher);

        timeTableButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                int requested = parseInt(nodes.getText().toString(), draft.nodeCount());
                if (requested < 1 || requested > TimeTableDraft.MAX_NODES) {
                    error.setText("每天节数必须在 1 到 " + TimeTableDraft.MAX_NODES + " 之间");
                    return;
                }
                draft.setNodeCount(requested);
                updateTimeTableButton.run();
                final Runnable syncCountField = new Runnable() {
                    @Override
                    public void run() {
                        // 编辑弹窗增删节后，把单一数据源的节数回填到输入框。
                        // 临时摘掉监听避免回填再触发 setNodeCount（虽幂等，但会白跑一次）。
                        nodes.removeTextChangedListener(nodesWatcher);
                        nodes.setText(String.valueOf(draft.nodeCount()));
                        nodes.addTextChangedListener(nodesWatcher);
                        updateTimeTableButton.run();
                    }
                };
                showTimeTableEditor(draft, updateTimeTableButton, syncCountField);
            }
        });
        final Calendar start = Calendar.getInstance();
        if (config.startDate != null && config.startDate.matches("\\d{4}-\\d{2}-\\d{2}")) {
            start.set(Integer.parseInt(config.startDate.substring(0, 4)),
                    Integer.parseInt(config.startDate.substring(5, 7)) - 1,
                    Integer.parseInt(config.startDate.substring(8, 10)));
        }
        Runnable updateStart = new Runnable() {
            @Override
            public void run() {
                startButton.setText("学期开始日期：" + TimetableLogic.dateKey(start.getTime()));
            }
        };
        updateStart.run();
        startButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                new DatePickerDialog(activity, new DatePickerDialog.OnDateSetListener() {
                    @Override
                    public void onDateSet(android.widget.DatePicker view, int year, int month, int day) {
                        start.set(year, month, day);
                        updateStart.run();
                    }
                }, start.get(Calendar.YEAR), start.get(Calendar.MONTH), start.get(Calendar.DAY_OF_MONTH)).show();
            }
        });

        maxWeek.setText(String.valueOf(config.maxWeek));
        nodes.setText(String.valueOf(config.nodes));
        showSat.setChecked(config.showSat);
        showSun.setChecked(config.showSun);
        showOther.setChecked(config.showOtherWeekCourse);

        final AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle("课表设置")
                .setView(view)
                .setNegativeButton("取消", null)
                .setPositiveButton("保存", null)
                .create();
        dialog.show();
        dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                int week = parseInt(maxWeek.getText().toString(), 0);
                int nodeCount = parseInt(nodes.getText().toString(), 0);
                if (week < 1 || week > 30) {
                    error.setText("总周数必须在 1 到 30 之间");
                    return;
                }
                if (nodeCount < 1 || nodeCount > 20) {
                    error.setText("每天节数必须在 1 到 20 之间");
                    return;
                }
                nodes.removeTextChangedListener(nodesWatcher);
                draft.setNodeCount(nodeCount);
                String timeError = TimeTableSettings.validateForSave(
                        draft.nodes(), draft.nodeCount());
                if (!timeError.isEmpty()) {
                    java.util.List<TimeTableDraft.Issue> issues = draft.issues();
                    error.setText(issues.isEmpty() ? timeError : TimeTableDraft.summarize(issues));
                    return;
                }
                TimetableConfig next = new TimetableConfig(TimetableLogic.dateKey(start.getTime()),
                        week, nodeCount, showSat.isChecked(), showSun.isChecked(), showOther.isChecked());
                // 先存时间表草稿再存配置：saveConfig 按同一规则联动，不会覆盖用户填的值。
                if (!repository.saveTimeNodes(draft.nodes())) {
                    error.setText("时间表保存失败，请检查各节起止时间");
                    return;
                }
                repository.saveConfig(next);
                dialog.dismiss();
                refresh();
            }
        });
    }

    /**
     * 节次时间表编辑器。
     *
     * <p><b>交互原则（用户明确要求）：</b></p>
     * <ul>
     *   <li><b>编辑过程完全不打扰</b>：改完一节只静默更新该行文本并把有问题的行标红，
     *       不弹任何 Toast / 对话框——连续调多节时不会被打断；</li>
     *   <li><b>点「保存」才校验整表</b>：交叉、起止倒置、格式错误都在这时检查；
     *       有问题用胶囊条提示第一条（不阻断，也不落盘），无问题则保存并提示成功；</li>
     *   <li>增/删节、恢复默认都不触发校验提醒（恢复默认得到的是必然合法的默认表，
     *       但为了让用户能立刻看到结果，这里直接落盘并提示成功）。</li>
     * </ul>
     *
     * <p>节数与时间统一由 {@link TimeTableDraft} 持有，本方法不另存一份计数。</p>
     */
    private void showTimeTableEditor(final TimeTableDraft draft, final Runnable onChange,
                                     final Runnable syncCountField) {
        final View view = inflater.inflate(R.layout.dialog_time_table, null, false);
        final LinearLayout list = view.findViewById(R.id.time_table_list);
        final TextView summary = view.findViewById(R.id.time_table_summary);
        final TextView error = view.findViewById(R.id.time_table_error);
        final Button reset = view.findViewById(R.id.button_time_table_reset);
        final Button addNode = view.findViewById(R.id.button_time_table_add);
        final Button removeNode = view.findViewById(R.id.button_time_table_remove);
        final Button save = view.findViewById(R.id.button_time_table_save);
        final CapsuleToast capsule = CapsuleToast.create(activity);

        final AlertDialog[] holder = new AlertDialog[1];
        final Runnable rerender = new Runnable() {
            @Override
            public void run() {
                renderTimeTableRows(list, draft, this, summary);
            }
        };

        // 恢复默认：默认表必然合法，直接落盘生效；不改当前节数。
        reset.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                draft.resetDefaults();
                rerender.run();
                onChange.run();
                if (repository.saveTimeNodes(draft.nodes())) {
                    if (capsule != null) capsule.show("已恢复默认时间表（" + draft.nodeCount() + " 节）");
                } else if (capsule != null) {
                    capsule.show("默认时间表保存失败");
                }
            }
        });

        // 增删节：不校验、不提醒，只同步草稿与输入框。
        addNode.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!draft.addNode()) {
                    if (capsule != null) capsule.show("最多 " + TimeTableDraft.MAX_NODES + " 节");
                    return;
                }
                rerender.run();
                onChange.run();
                syncCountField.run();
            }
        });
        removeNode.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!draft.removeNode()) {
                    if (capsule != null) capsule.show("至少保留 " + TimeTableDraft.MIN_NODES + " 节");
                    return;
                }
                rerender.run();
                onChange.run();
                syncCountField.run();
            }
        });

        // 保存：唯一的校验入口。
        save.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // 与落库同源校验：用草稿自己的节数，而不是库里的旧节数。
                String problem = TimeTableSettings.validateForSave(
                        draft.nodes(), draft.nodeCount());
                if (!problem.isEmpty()) {
                    // 列出所有问题行（节次号 + 原因），让用户一轮改完，而不是只看到第一条。
                    java.util.List<TimeTableDraft.Issue> issues = draft.issues();
                    String detail = issues.isEmpty() ? problem : TimeTableDraft.summarize(issues);
                    if (capsule != null) capsule.show(detail);
                    rerender.run();
                    return;
                }
                if (!repository.saveTimeNodes(draft.nodes())) {
                    // 走到这里说明数据本身有问题（不是节数错位）——提示真实原因而非笼统文案。
                    java.util.List<TimeTableDraft.Issue> issues = draft.issues();
                    String detail = issues.isEmpty()
                            ? "时间表保存失败，请检查各节起止时间"
                            : TimeTableDraft.summarize(issues);
                    if (capsule != null) capsule.show(detail);
                    rerender.run();
                    return;
                }
                if (capsule != null) capsule.show("时间表已保存（" + draft.nodeCount() + " 节）");
                onChange.run();
                AlertDialog dialog = holder[0];
                if (dialog != null) dialog.dismiss();
            }
        });

        rerender.run();
        holder[0] = new AlertDialog.Builder(activity)
                .setTitle("节次时间表")
                .setView(view)
                .setNegativeButton("关闭", null)
                .show();
        holder[0].setOnDismissListener(new android.content.DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(android.content.DialogInterface dialog) {
                // 关闭后让设置弹窗的按钮文案与输入框跟草稿保持一致。
                onChange.run();
                syncCountField.run();
            }
        });
    }

    /**
     * 渲染 1..N 节。有问题的行（时间倒置/与上一节交叉）只把文字标红，
     * 不弹任何提示——编辑过程中保持安静。
     */
    private void renderTimeTableRows(final LinearLayout list, final TimeTableDraft draft,
                                     final Runnable rerender, final TextView summary) {
        list.removeAllViews();
        int count = draft.nodeCount();
        java.util.Map<Integer, String> issues = new java.util.HashMap<>();
        for (TimeTableDraft.Issue issue : draft.issues()) {
            issues.put(issue.node, issue.message);
        }
        for (int index = 1; index <= count; index++) {
            final int node = index;
            TimeNode item = draft.node(node);
            if (item == null) item = TimeTableSettings.defaultNode(node);
            final TimeNode current = item;
            View row = inflater.inflate(R.layout.item_time_node, list, false);
            TextView label = row.findViewById(R.id.time_node_label);
            TextView range = row.findViewById(R.id.time_node_range);
            label.setText("第 " + node + " 节");
            range.setText(current.startTime + " - " + current.endTime);
            String problem = issues.get(node);
            if (problem != null) {
                int warn = android.os.Build.VERSION.SDK_INT >= 23
                        ? activity.getResources().getColor(R.color.statusMissedText, activity.getTheme())
                        : activity.getResources().getColor(R.color.statusMissedText);
                label.setTextColor(warn);
                range.setTextColor(warn);
            }
            row.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    pickTime(draft, node, current.startTime, true, rerender, summary);
                }
            });
            list.addView(row);
        }
        int issueCount = issues.size();
        summary.setText("共 " + count + " 节；点击任意一节修改时间"
                + (issueCount > 0 ? "（红字 " + issueCount + " 处待修正）" : ""));
    }

    /**
     * 两步选择时间：先选开始，再以该值为准选结束。
     *
     * <p><b>不做任何校验提示：</b>选择结果直接写入草稿并重绘，非法组合只会让该行变红。
     * 真正的校验发生在点「保存」时。</p>
     */
    private void pickTime(final TimeTableDraft draft, final int node, final String startValue,
                          final boolean startStep, final Runnable rerender,
                          final TextView summary) {
        TimeNode current = draft.node(node);
        String initial = startStep ? startValue
                : (current == null ? "08:45" : current.endTime);
        int minutes = TimeTableSettings.minutes(initial);
        if (minutes < 0) minutes = startStep ? 8 * 60 : 8 * 60 + 45;
        new TimePickerDialog(activity, new TimePickerDialog.OnTimeSetListener() {
            @Override
            public void onTimeSet(android.widget.TimePicker picker, int hour, int minute) {
                String value = String.format(Locale.US, "%02d:%02d", hour, minute);
                if (startStep) {
                    pickTime(draft, node, value, false, rerender, summary);
                    return;
                }
                // 静默写入：非法/交叉都不在这里拦，交给保存时的整表校验。
                draft.setNodeTime(node, startValue, value);
                rerender.run();
            }
        }, minutes / 60, minutes % 60, true).show();
    }

    private void openCsvPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        try {
            activity.startActivityForResult(intent, REQUEST_CSV);
        } catch (Exception ignored) {
            Toast.makeText(activity, "无法打开文件选择器", Toast.LENGTH_SHORT).show();
        }
    }

    private void openHtmlPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        // text/html 过滤在部分文件管理器上会隐藏 .html 文件，放宽为 */*
        intent.setType("*/*");
        try {
            activity.startActivityForResult(intent, REQUEST_HTML);
        } catch (Exception ignored) {
            Toast.makeText(activity, "无法打开文件选择器", Toast.LENGTH_SHORT).show();
        }
    }

    /** 教务系统导入：内嵌 WebView 登录，页面完成后提取 table 交给通用 HTML 解析器。 */
    private void showOnlineImport() {
        onlineBases.clear();
        onlineDetails.clear();
        onlineWarnings.clear();
        onlineParsing.set(false);
        onlineResourceState.reset();
        onlineCapturePool.clear();
        onlineLastCourseCount = -1;
        onlineLastDetailCount = -1;
        selectedSchool = loadSavedSchool();
        deepScanRequested = false;

        View dialogView = inflater.inflate(R.layout.dialog_online_import, null, false);
        final EditText urlInput = dialogView.findViewById(R.id.input_online_url);
        final TextView status = dialogView.findViewById(R.id.text_online_status);
        final WebView webView = dialogView.findViewById(R.id.webview_online);
        currentWebView = webView;
        final TextView guideOverlay = dialogView.findViewById(R.id.online_guide_overlay);
        final TextView selectedSchoolView = dialogView.findViewById(R.id.text_online_selected_school);
        final Button schoolButton = dialogView.findViewById(R.id.button_online_school);
        final Button removeSchool = dialogView.findViewById(R.id.button_online_remove_school);
        final Button go = dialogView.findViewById(R.id.button_online_go);
        final Button history = dialogView.findViewById(R.id.button_online_history);
        final Button extract = dialogView.findViewById(R.id.button_online_extract);
        final Button diagnosticsButton = dialogView.findViewById(R.id.button_online_diagnostics);
        final Button importButton = dialogView.findViewById(R.id.button_online_import);
        Button cancel = dialogView.findViewById(R.id.button_online_cancel);

        // 被动捕获开关：默认开启，持久化到偏好。用户遇登录异常可一键关闭自救，
        // 关闭后 PassiveCaptureClient 对所有请求返回 null，等同原生 WebViewClient。
        final CheckBox captureToggle = dialogView.findViewById(R.id.check_online_capture);
        if (captureToggle != null) {
            captureToggle.setChecked(loadCaptureEnabled());
            captureToggle.setText("被动捕获");
            captureToggle.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(CompoundButton button, boolean checked) {
                    saveCaptureEnabled(checked);
                    PassiveCaptureClient client = onlineCaptureClient;
                    if (client != null) client.setEnabled(checked);
                    if (!checked) {
                        onlineCapturePool.clear();
                    }
                    if (!onlineParsing.get()) {
                        refreshCaptureStatus(status);
                    }
                }
            });
        }

        // 选校专用 parser 以插件形式注入注册表：能处理时优先级最高，否则自然落到
        // 新正方 / KingoGrid / 通用网格。每次打开对话框按当前选校重置，
        // 避免上一所学校的规则残留。
        DedicatedParserPlugin.setDelegate(new DedicatedParserPlugin.Delegate() {
            @Override
            public HtmlTimetableParser.Result parse(String body) {
                WakeupParserResult dedicated = parseWithDedicatedParser(body);
                if (dedicated == null || dedicated.isEmpty()) return null;
                return mapped(dedicated);
            }
        });
        configureWebView(webView, status, guideOverlay);
        updateSchoolViews(selectedSchoolView, schoolButton);
        if (selectedSchool != null) urlInput.setText(selectedSchool.url);
        schoolButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                showSchoolPicker(webView, urlInput, status, selectedSchoolView, schoolButton);
            }
        });
        removeSchool.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                selectedSchool = null;
                onlinePreferences().edit().remove("school_name").remove("school_type")
                        .remove("school_import_type").remove("school_url").apply();
                updateSchoolViews(selectedSchoolView, schoolButton);
                updateOnlineFamilyGuide();
                status.setText("已清除学校选择，可输入网址或重新选择学校");
            }
        });
        urlInput.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView view, int actionId, KeyEvent event) {
                if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_GO) {
                    loadOnlineUrl(webView, urlInput, status);
                    return true;
                }
                return false;
            }
        });
        go.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                loadOnlineUrl(webView, urlInput, status);
            }
        });
        history.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                showOnlineHistoryPicker(webView, urlInput, status);
            }
        });
        extract.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                extractOnlinePage(webView, status, true);
            }
        });
        // 通道 3 入口：长按「提取课表」开启深度扫描（几何 + DOM 全量）。默认关闭，
        // 因为它是旧架构里最容易卡住的路径，只在轻发现 + fetch 都拿不到时才有价值。
        final Button stopBtn = dialogView.findViewById(R.id.button_online_stop);
        stopBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                stopOnlineExtraction(status);
            }
        });
        extract.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View view) {
                deepScanRequested = true;
                Toast.makeText(activity, "已开启深度扫描：再点一次「提取课表」执行（较慢）",
                        Toast.LENGTH_LONG).show();
                status.setText("深度扫描已就绪：点「提取课表」开始（较慢，前两种通道失败后再用）");
                return true;
            }
        });
        diagnosticsButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                showOnlineDiagnostics();
            }
        });
        status.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View view) {
                showOnlineDiagnostics();
                return true;
            }
        });
        Button framesButton = dialogView.findViewById(R.id.button_online_frames);
        framesButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                showFramePicker(webView);
            }
        });
        importButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                // 手动模型：导入只使用最近一次「提取课表」的结果，不再现扫。
                if (onlineDetails.isEmpty()) {
                    Toast.makeText(activity, "尚未捕获到课程：请先点「提取课表」", Toast.LENGTH_LONG).show();
                    return;
                }
                onlineImportDialog.dismiss();
                showOnlineImportPreview();
            }
        });
        cancel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                onlineImportDialog.dismiss();
            }
        });

        Dialog dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(dialogView);
        onlineImportDialog = dialog;
        dialog.setOnDismissListener(new android.content.DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(android.content.DialogInterface dialogInterface) {
                // 取消顺序：① 让 JS 侧 abort 标志置位，正在跑的 fetch 在下一个检查点退出；
                // ② 移除 JavascriptInterface，之后任何迟到回调都进不来；
                // ③ 关闭 session（cancel deadline + 关桥接 + shutdownNow）；
                // ④ 最后才 destroy WebView。顺序错了会出现「回调打到已销毁 WebView」。
                try {
                    webView.evaluateJavascript(OnlineExtraction.abortAllScript(), null);
                } catch (RuntimeException ignored) {
                    // WebView 可能已不可用，继续走后面的强制回收。
                }
                try {
                    webView.removeJavascriptInterface(SINK_NAME);
                } catch (RuntimeException ignored) {
                    // 未注入过时忽略。
                }
                OnlineExtractionSession session = activeSession;
                activeSession = null;
                if (session != null) {
                    session.log("对话框关闭：取消在途提取");
                    session.close();
                }
                Thread watcher = watchdog;
                watchdog = null;
                if (watcher != null) watcher.interrupt();
                PassiveCaptureClient capture = onlineCaptureClient;
                onlineCaptureClient = null;
                if (capture != null) capture.close();
                onlineCapturePool.clear();
                DedicatedParserPlugin.setDelegate(null);
                CookieManager.getInstance().flush();
                currentWebView = null;
                webView.destroy();
                onlineParsing.set(false);
                onlineImportDialog = null;
            }
        });
        dialog.show();
        Window window = dialog.getWindow();
        if (window != null) {
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            window.setBackgroundDrawableResource(R.color.background);
        }
        updateOnlineFamilyGuide();
    }

    private void updateOnlineFamilyGuide() {
        if (onlineImportDialog == null) return;
        TextView guide = onlineImportDialog.findViewById(R.id.text_online_family_guide);
        if (guide == null) return;
        String type = selectedSchool == null ? "" : selectedSchool.type;
        String importType = selectedSchool == null ? "" : selectedSchool.importType;
        guide.setText(OnlineImportLogic.guideFor(type, importType));
    }

    private void configureWebView(final WebView webView, final TextView status,
                                  final View guideOverlay) {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setSupportZoom(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        cookies.setAcceptThirdPartyCookies(webView, true);

        PassiveCaptureClient captureClient = new PassiveCaptureClient(activity, new PassiveCaptureClient.Listener() {
            @Override
            public void onCaptured(final PassiveCapture.Captured captured) {
                // 后台线程：只写池 + 更新计数，UI 刷新投递回 UI 线程。
                boolean added = onlineCapturePool.add(captured);
                if (added) {
                    runOnUi(new Runnable() {
                        @Override
                        public void run() {
                            refreshCaptureStatus(status);
                        }
                    });
                }
            }
        }, new PassiveCaptureClient.Navigation() {
            @Override
            public void onStarted(String url) {
                guideOverlay.setVisibility(View.GONE);
                status.setText("正在加载 " + url);
            }

            @Override
            public void onFinished(String url) {
                // 手动提取模型：加载完成只更新提示。
                refreshCaptureStatus(status);
            }

            @Override
            public void onMainFrameError() {
                status.setText("页面加载失败：请检查网址、网络或学校服务状态");
            }
        });
        captureClient.setEnabled(loadCaptureEnabled());
        onlineCaptureClient = captureClient;
        webView.setWebViewClient(captureClient);
    }

    /**
     * 状态栏实时显示被动捕获进度。
     * 只在当前没在提取时覆盖状态栏，避免把提取进度文案冲掉。
     */
    private void refreshCaptureStatus(TextView status) {
        if (status == null || onlineParsing.get()) return;
        if (!loadCaptureEnabled()) {
            status.setText("被动捕获已关闭（不再拦截任何请求）；打开课表页面后点「提取课表」");
            return;
        }
        status.setText(onlineCapturePool.summary()
                + "；打开课表数据页面并执行查询后点「提取课表」");
    }

    /** 被动捕获偏好：默认开启。 */
    private boolean loadCaptureEnabled() {
        return onlinePreferences().getBoolean("passive_capture_enabled", true);
    }

    private void saveCaptureEnabled(boolean enabled) {
        onlinePreferences().edit().putBoolean("passive_capture_enabled", enabled).apply();
    }

    private void loadOnlineUrl(WebView webView, EditText urlInput, TextView status) {
        String url = OnlineImportLogic.normalizeUrl(urlInput.getText().toString());
        if (url.isEmpty()) {
            Toast.makeText(activity, "请输入 http 或 https 教务网址", Toast.LENGTH_SHORT).show();
            return;
        }
        urlInput.setText(url);
        saveOnlineHistory(OnlineImportLogic.history(loadOnlineHistory(), url));
        status.setText("正在加载 " + url);
        webView.loadUrl(url);
    }

    private void showOnlineHistoryPicker(final WebView webView, final EditText urlInput,
                                          final TextView status) {
        final List<String> urls = loadOnlineHistory();
        if (urls.isEmpty()) {
            Toast.makeText(activity, "暂无历史网址", Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(activity)
                .setTitle("最近访问")
                .setItems(urls.toArray(new String[0]), new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        urlInput.setText(urls.get(which));
                        status.setText("正在加载 " + urls.get(which));
                        webView.loadUrl(urls.get(which));
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ------------------------------------------------------------------
    // frame HTML 转储通道
    // ------------------------------------------------------------------

    /**
     * 「框架」按钮：发现 frame → 列出可勾选项（轻量信息）→ 用户勾选 → 提取选中。
     *
     * <p><b>为什么默认手动选择：</b>用户明确表示手动选区域是首选。自动 dump 全部 frame
     * 在复杂教务页上会一次序列化多个大 DOM，既慢又常把无关 frame 也搬进来。
     * 这里只做「列出 + 轻量探测」，真正的 outerHTML 序列化只发生在用户点「提取选中框架」之后。</p>
     *
     * <p><b>脚本严格分离：</b>列表阶段调用 {@link FrameDump#probeScript}（只读 table 数量、
     * 正文前 60 字、子 frame 数），绝不触碰 outerHTML；dump 阶段才用
     * {@link FrameDump#dumpScript}。</p>
     */
    private void showFramePicker(final WebView webView) {
        final View view = inflater.inflate(R.layout.dialog_online_frames, null, false);
        final LinearLayout list = view.findViewById(R.id.frames_list);
        final TextView summary = view.findViewById(R.id.frames_summary);
        final TextView status = view.findViewById(R.id.frames_status);
        final Button extract = view.findViewById(R.id.button_frames_extract);
        final Button probe = view.findViewById(R.id.button_frames_probe);
        final Button selectAll = view.findViewById(R.id.button_frames_select_all);
        final Button clearAll = view.findViewById(R.id.button_frames_clear);

        final List<FrameDump.Candidate> candidates = new ArrayList<>();
        final Dialog picker = new Dialog(activity);
        picker.requestWindowFeature(Window.FEATURE_NO_TITLE);
        picker.setContentView(view);

        final Runnable render = new Runnable() {
            @Override
            public void run() {
                list.removeAllViews();
                for (final FrameDump.Candidate candidate : candidates) {
                    CheckBox box = new CheckBox(activity);
                    box.setText(candidate.label());
                    box.setTextSize(12f);
                    box.setChecked(candidate.selected);
                    box.setEnabled(candidate.accessible());
                    box.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
                        @Override
                        public void onCheckedChanged(android.widget.CompoundButton button, boolean checked) {
                            candidate.setSelected(checked);
                            updateFrameSummary(summary, candidates);
                        }
                    });
                    list.addView(box);
                }
                updateFrameSummary(summary, candidates);
            }
        };

        selectAll.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                for (FrameDump.Candidate candidate : candidates) candidate.setSelected(true);
                render.run();
            }
        });
        clearAll.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                for (FrameDump.Candidate candidate : candidates) candidate.setSelected(false);
                render.run();
            }
        });
        probe.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                probeFrames(webView, candidates, render, status);
            }
        });
        extract.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                List<FrameDump.Candidate> chosen = FrameDump.selected(candidates);
                if (chosen.isEmpty()) {
                    status.setText("请至少勾选一个框架");
                    return;
                }
                picker.dismiss();
                dumpFrames(webView, chosen);
            }
        });

        picker.show();
        Window window = picker.getWindow();
        if (window != null) {
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        }

        status.setText("正在发现框架…");
        discoverFramesForPicker(webView, candidates, render, status);
    }

    /**
     * 列表阶段：先列 frame 路径，再逐帧取轻量信息。
     *
     * <p><b>为什么两次而不是一次：</b>真机反馈「框架发现超时」后刷新又变成 0 个文档。
     * 旧实现一个同步脚本递归遍历所有文档并在每个文档上跑
     * {@code performance.getEntriesByType('resource')}，在 WebVPN+echarts 页面上主线程被占满
     * 数秒 → 超时 → <b>整个结果丢失</b>。拆开后：</p>
     * <ol>
     *   <li>{@link OnlineExtraction#listFramesScript()}：只列路径，超轻量，10s 预算；</li>
     *   <li>{@link FrameDump#frameInfoScript(String)}：逐帧单独 evaluate，单帧 2s，
     *       某帧失败/超时<b>只丢那一帧</b>，其余照常展示。</li>
     * </ol>
     *
     * <p><b>为什么必须异步（UI 线程死锁教训）：</b>本方法由「框架」按钮的 {@code onClick}
     * 在 UI 线程调用。旧实现用 {@code evaluateForPicker} 同步等回调——{@code latch.await}
     * 阻塞 UI 线程，而 {@code ValueCallback.onReceiveValue} 恰恰要投递回 UI 线程：
     * 等待方阻塞了投递方，回调永远进不来 → <b>每次必现 10s 超时 + 0 个文档</b>。
     * 现在改走 {@link #evaluateAsync}：UI 线程只投递不等待，结果或超时经回调回 UI 线程再续跑。</p>
     *
     * <p>每次点「刷新列表」都从头重新探测：<b>不走任何去重集合</b>，也不复用上一轮结果。
     * 轮次号（{@link #frameDiscoveryGeneration}）保证刷新后旧回调整体作废。</p>
     */
    private void discoverFramesForPicker(final WebView webView, final List<FrameDump.Candidate> out,
                                         final Runnable render, final TextView status) {
        // 状态复位：上一轮可能卡在超时，这里必须把列表与提示都清空，避免"刷新后 0 个文档"
        // 被误读成"页面没有 frame"。
        out.clear();
        render.run();
        final int generation = frameDiscoveryGeneration.incrementAndGet();
        status.setText("正在发现框架…");

        final long started = System.currentTimeMillis();
        evaluateAsync(webView, OnlineExtraction.listFramesScript(),
                OnlineExtractionSession.DISCOVER_TIMEOUT_MS, new ValueCallback<String>() {
                    @Override
                    public void onReceiveValue(String raw) {
                        // 轮次已变：用户已刷新，本轮结果整体作废（列表已被新一轮 clear/重建）。
                        if (generation != frameDiscoveryGeneration.get()) return;
                        if (raw == null) {
                            long elapsed = System.currentTimeMillis() - started;
                            status.setText("框架发现超时（" + elapsed + "ms），请稍后点「刷新列表」重试；"
                                    + "若反复超时，可直接用「提取课表」");
                            return;
                        }
                        OnlineExtraction.Discovery discovery = OnlineExtraction.parseDiscovery(raw);
                        // 每次全新构造候选（不复用上一轮对象），保证勾选状态不会串到新一轮。
                        out.addAll(FrameDump.candidates(discovery));
                        render.run();
                        if (out.isEmpty()) {
                            status.setText("页面里没有可用 frame（" + (discovery.error.isEmpty()
                                    ? "可能尚未加载完成" : discovery.error) + "）；点「刷新列表」重试");
                            return;
                        }
                        status.setText("已发现 " + out.size() + " 个文档（可访问 "
                                + discovery.accessibleFrames().size() + " 个，"
                                + (System.currentTimeMillis() - started) + "ms）；点击「刷新列表」可重新探测");
                        // 拿到路径后再逐帧取信息；单帧失败只影响该帧。
                        probeFrames(webView, out, render, status);
                    }
                });
    }

    /**
     * 异步注入脚本：立即返回，结果（或超时 null / WebView 异常 null）经回调在 UI 线程送达。
     *
     * <p><b>死锁教训：</b>UI 线程上绝不允许 {@code latch.await} 等待
     * {@code evaluateJavascript} 的回调——回调本身要投递回 UI 线程，等待方会阻塞投递方。
     * 本方法用超时看门狗（{@code postDelayed}）+ {@link OneShotGate} 仲裁三个终点
     * （回调/超时/异常）只算一路：看门狗先触发则晚到的回调作废；回调先到则撤销看门狗，
     * 撤不到的靠门槛兜底。后台线程如需同步语义仍可用 {@link #evaluateForPicker}
     * （那里 await 的是后台线程，回调走 UI 线程，不构成死锁）。</p>
     */
    private void evaluateAsync(final WebView webView, final String script, int timeoutMs,
                               final ValueCallback<String> callback) {
        final android.os.Handler ui = new android.os.Handler(android.os.Looper.getMainLooper());
        final OneShotGate gate = new OneShotGate();
        final Runnable timeout = new Runnable() {
            @Override
            public void run() {
                if (gate.settle()) callback.onReceiveValue(null);
            }
        };
        ui.postDelayed(timeout, Math.max(0, timeoutMs));
        runOnUi(new Runnable() {
            @Override
            public void run() {
                if (gate.isSettled()) return;
                try {
                    webView.evaluateJavascript(script, new ValueCallback<String>() {
                        @Override
                        public void onReceiveValue(String value) {
                            if (gate.settle()) {
                                ui.removeCallbacks(timeout);
                                callback.onReceiveValue(value);
                            }
                        }
                    });
                } catch (RuntimeException exception) {
                    // WebView 已销毁/正在导航：立即以超时语义结束，而不是干等到看门狗。
                    if (gate.settle()) {
                        ui.removeCallbacks(timeout);
                        callback.onReceiveValue(null);
                    }
                }
            }
        });
    }

    /**
     * 在 UI 线程注入脚本并等待回调。
     *
     * @return 脚本返回值；超时/异常返回 null（调用方据此给出"可重试"的提示）
     */
    private String evaluateForPicker(final WebView webView, final String script, int timeoutMs) {
        final String[] holder = new String[1];
        final CountDownLatch latch = new CountDownLatch(1);
        runOnUi(new Runnable() {
            @Override
            public void run() {
                try {
                    webView.evaluateJavascript(script, new ValueCallback<String>() {
                        @Override
                        public void onReceiveValue(String value) {
                            holder[0] = value;
                            latch.countDown();
                        }
                    });
                } catch (RuntimeException exception) {
                    // WebView 已销毁/正在导航：立即让等待方失败，而不是干等到超时。
                    latch.countDown();
                }
            }
        });
        try {
            if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) return null;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return null;
        }
        return holder[0];
    }

    /**
     * 逐帧轻量探测：每个 frame 一次独立 evaluateJavascript。
     *
     * <p>串行执行（并发注入会让 WebView 主线程排队，低端机上更慢）；单帧 2s 超时，
     * 失败的帧保留 `tableCount=-1`（列表显示为未知），不影响其它帧。</p>
     */
    private void probeFrames(final WebView webView, final List<FrameDump.Candidate> candidates,
                             final Runnable render, final TextView status) {
        final List<FrameDump.Candidate> targets = new ArrayList<>(candidates);
        // 轮次守卫：探测期间用户点「刷新列表」会开新一轮（generation++），
        // 旧探测线程的任何状态写入都必须作废，不得覆盖新一轮的提示。
        final int generation = frameDiscoveryGeneration.get();
        new Thread(new Runnable() {
            @Override
            public void run() {
                int done = 0;
                int failed = 0;
                for (FrameDump.Candidate candidate : targets) {
                    if (!candidate.accessible()) continue;
                    if (!probeOne(webView, candidate.path(), candidate)) failed++;
                    done++;
                    if (generation != frameDiscoveryGeneration.get()) return;
                    final int progress = done;
                    final int total = targets.size();
                    runOnUi(new Runnable() {
                        @Override
                        public void run() {
                            status.setText("探测框架 " + progress + "/" + total
                                    + "…（超时或失败不影响其它帧）");
                        }
                    });
                }
                if (generation != frameDiscoveryGeneration.get()) return;
                final int failCount = failed;
                runOnUi(new Runnable() {
                    @Override
                    public void run() {
                        render.run();
                        status.setText(failCount == 0
                                ? "探测完成，勾选要提取的框架"
                                : "探测完成（" + failCount + " 个帧未能读取信息），勾选要提取的框架");
                    }
                });
            }
        }, "frame-probe").start();
    }

    /** 单个 frame 的轻量探测；返回 false 表示该帧超时或读取失败（只影响这一帧）。 */
    private boolean probeOne(WebView webView, String path, FrameDump.Candidate candidate) {
        String raw = evaluateForPicker(webView, FrameDump.frameInfoScript(path),
                FrameDump.PROBE_TIMEOUT_MS);
        if (raw == null) return false;
        int[] values = FrameDump.parseProbe(raw);
        candidate.withInfo(values[0], FrameDump.parseProbeSummary(raw), values[1]);
        return values[0] >= 0;
    }

    /**
     * 提取选中 frame：逐个 dump → 分块回传 → 逐 frame 解析合并。
     *
     * <p>超时矩阵：单 frame 8s、总预算 60s（沿用
     * {@link FrameDump#PER_FRAME_TIMEOUT_MS} / {@link FrameDump#TOTAL_BUDGET_MS}）。
     * 进度逐 frame 汇报「正在提取 2/3：…」。</p>
     */
    private void dumpFrames(final WebView webView, final List<FrameDump.Candidate> chosen) {
        if (!onlineParsing.compareAndSet(false, true)) return;
        if (!onlineExtractionState.start(System.currentTimeMillis())) {
            onlineParsing.set(false);
            return;
        }
        final OnlineExtractionSession session = new OnlineExtractionSession();
        activeSession = session;
        try {
            webView.removeJavascriptInterface(SINK_NAME);
        } catch (RuntimeException ignored) {
            // 首次注入不存在
        }
        try {
            webView.addJavascriptInterface(session.bridge(), SINK_NAME);
        } catch (RuntimeException exception) {
            session.log("接收器注入失败：" + exception.getMessage());
            onlineParsing.set(false);
            onlineExtractionState.stop(System.currentTimeMillis());
            return;
        }
        final int maxWeek = repository.getConfig().maxWeek;
        final int nodes = repository.getConfig().nodes;
        final List<String[]> dumps = new ArrayList<>();
        final long totalDeadline = System.currentTimeMillis() + FrameDump.TOTAL_BUDGET_MS;
        final TextView status = onlineImportDialog == null ? null
                : (TextView) onlineImportDialog.findViewById(R.id.text_online_status);
        final Button extractButton = onlineImportDialog == null ? null
                : (Button) onlineImportDialog.findViewById(R.id.button_online_extract);
        final Button stopButton = onlineImportDialog == null ? null
                : (Button) onlineImportDialog.findViewById(R.id.button_online_stop);
        final UiProgress progress = new UiProgress(session, status, extractButton, stopButton);
        if (extractButton != null) extractButton.setVisibility(View.GONE);
        if (stopButton != null) {
            stopButton.setVisibility(View.VISIBLE);
            stopButton.setEnabled(true);
            stopButton.setText("停止");
        }

        new Thread(new Runnable() {
            @Override
            public void run() {
                String fatal = "";
                for (int i = 0; i < chosen.size(); i++) {
                    final int index = i + 1;
                    final FrameDump.Candidate candidate = chosen.get(i);
                    if (System.currentTimeMillis() > totalDeadline) {
                        fatal = "总预算 60s 耗尽，剩余 " + (chosen.size() - i) + " 个框架未提取";
                        break;
                    }
                    final String label = "frame#" + candidate.index + " " + FrameDump.urlTail(candidate.frame.url);
                    progress.status("正在提取 " + index + "/" + chosen.size()
                            + "：" + FrameDump.urlTail(candidate.frame.url));
                    session.log("提取 " + index + "/" + chosen.size() + "：" + candidate.label());
                    String html = dumpOneFrame(session, webView, candidate);
                    if (html == null) {
                        session.log("  └ 失败或超时，跳过");
                        dumps.add(new String[]{label, candidate.frame.url, ""});
                        continue;
                    }
                    session.log("  └ 收到 " + html.length() + " 字符");
                    dumps.add(new String[]{label, candidate.frame.url, html});
                }

                FrameDump.Report report = FrameDump.parseAll(dumps, maxWeek, nodes);
                for (FrameDump.FrameResult result : report.results) {
                    session.log("解析 " + result.describe());
                }
                session.log("汇总：" + report.summary());
                String note = report.results.isEmpty() ? "" : report.summary();
                finishExtraction(session, progress, report.merged, fatal, note, true);
            }
        }, "frame-dump").start();
    }

    /** 单个 frame dump：注入 + 轮询分块拼装（复用 fetch 代理同一套协议）。 */
    private String dumpOneFrame(OnlineExtractionSession session, WebView webView,
                                FrameDump.Candidate candidate) {
        final String requestId = session.nextRequestId();
        final String script = FrameDump.dumpScript(requestId, candidate.frame.url,
                candidate.path());
        final CountDownLatch submitted = new CountDownLatch(1);
        runOnUi(new Runnable() {
            @Override
            public void run() {
                try {
                    webView.evaluateJavascript(script, new ValueCallback<String>() {
                        @Override
                        public void onReceiveValue(String value) {
                            submitted.countDown();
                        }
                    });
                } catch (RuntimeException exception) {
                    submitted.countDown();
                }
            }
        });
        if (!session.await(submitted, 2_000)) return null;

        long deadlineAt = System.currentTimeMillis() + FrameDump.PER_FRAME_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadlineAt) {
            if (session.isClosed()) return null;
            if (session.assembler.isDone(requestId)) {
                OnlineExtraction.Assembled result = session.assembler.result(requestId);
                if (result.complete()) {
                    String body = result.bodyText();
                    if (body.length() > FrameDump.PER_FRAME_MAX_CHARS) {
                        return body.substring(0, FrameDump.PER_FRAME_MAX_CHARS);
                    }
                    return body;
                }
                session.log("  ├ frame 回传失败：" + result.error);
                return null;
            }
            try {
                Thread.sleep(40L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        session.log("  ├ frame dump 超时（" + FrameDump.PER_FRAME_TIMEOUT_MS + "ms）");
        return null;
    }

    private void updateFrameSummary(TextView summary, List<FrameDump.Candidate> candidates) {
        int selected = FrameDump.selected(candidates).size();
        int accessible = 0;
        for (FrameDump.Candidate candidate : candidates) {
            if (candidate.accessible()) accessible++;
        }
        summary.setText("共 " + candidates.size() + " 个文档（可访问 " + accessible
                + "），已选 " + selected + "；勾选后点「提取选中框架」");
    }

    /**
     * 「提取课表」入口：新架构的编排层。
     *
     * <p>旧实现把巨型脚本的返回值当作唯一数据来源，一旦脚本卡住或返回 null 就无声无息地
     * 永远停在「提取中…」。新流程把它拆成三个阶段，每个阶段都有自己的超时与诊断：</p>
     * <pre>
     * ① 轻发现（3s）    discoveryScript() → frame 树 + performance 资源 → 候选 URL 排序
     * ② 按需取数据（逐步 8s，总 30s）
     *      主通道：页内 fetch 代理 → JavascriptInterface 分块回传 → ChunkAssembler 拼装
     *      回落  ：HttpURLConnection + 会话 Cookie 直取
     * ③ 解析合并        family parser → HtmlTimetableParser → OnlineUiParser/div → merge
     * ④ 展示            回到 UI 线程，按钮必然恢复
     * </pre>
     *
     * <p>所有阶段运行在 {@link OnlineExtractionSession#worker()} 单线程上，UI 线程只负责
     * 按钮文案与结果展示。</p>
     */
    private void extractOnlinePage(final WebView webView, final TextView status, final boolean manual) {
        if (onlineImportDialog == null || !onlineImportDialog.isShowing()
                || !onlineParsing.compareAndSet(false, true)) {
            return;
        }
        // 状态机：只有 IDLE 才能开始；重复点「停止」是幂等的。
        if (!onlineExtractionState.start(System.currentTimeMillis())) {
            onlineParsing.set(false);
            return;
        }
        final Button extractButton = onlineImportDialog.findViewById(R.id.button_online_extract);
        final Button stopButton = onlineImportDialog.findViewById(R.id.button_online_stop);
        if (extractButton != null) {
            // 提取中隐藏「提取课表」，原位置换成「停止」。
            extractButton.setVisibility(View.GONE);
        }
        if (stopButton != null) {
            stopButton.setVisibility(View.VISIBLE);
            stopButton.setEnabled(true);
            stopButton.setText("停止");
        }
        // 每轮都重置诊断，避免上一轮的残留内容让用户误以为「没跑」。
        onlineDiagnostics = "提取已开始…\n";

        final OnlineExtractionSession session = new OnlineExtractionSession();
        activeSession = session;
        session.log("开始提取（无时间上限，可随时点「停止」）");
        session.log("被动捕获池：" + onlineCapturePool.summary());

        // 注入分块接收器：必须先 remove 同名对象再 add，否则重复 add 会静默失败。
        try {
            webView.removeJavascriptInterface(SINK_NAME);
        } catch (RuntimeException ignored) {
            // 首次注入时不存在，忽略。
        }
        try {
            webView.addJavascriptInterface(session.bridge(), SINK_NAME);
            session.log("分块接收器已注入 " + SINK_NAME);
        } catch (RuntimeException exception) {
            session.log("接收器注入失败：" + exception.getMessage() + "（将只用 Cookie 直取）");
        }

        final int maxWeek = repository.getConfig().maxWeek;
        final int nodes = repository.getConfig().nodes;
        final String pageUrl = webView.getUrl() == null ? "" : webView.getUrl();
        final String userAgent = webView.getSettings().getUserAgentString();
        final String pageCookies = cookieFor(pageUrl);
        final boolean deep = deepScanRequested;
        deepScanRequested = false;
        session.log("页面：" + pageUrl);
        session.log("深度扫描：" + (deep ? "开启" : "关闭"));
        status.setText("正在发现页面结构…");
        session.deadline.enterPhase(OnlineExtraction.ExtractionDeadline.PHASE_DISCOVER, session.startMillis());

        // UI 线程副作用一律走这里，避免在后台线程碰 View。
        final UiProgress progress = new UiProgress(session, status, extractButton, stopButton);

        // 通道 1：轻发现。回调在 UI 线程，立即把后续全部工作投递给后台线程。
        final CountDownLatch discoverLatch = new CountDownLatch(1);
        final String[] discoverRaw = new String[1];
        try {
            webView.evaluateJavascript(OnlineExtraction.discoveryScript(), new ValueCallback<String>() {
                @Override
                public void onReceiveValue(String value) {
                    discoverRaw[0] = value;
                    discoverLatch.countDown();
                }
            });
        } catch (RuntimeException exception) {
            session.log("发现脚本注入失败：" + exception.getMessage());
            discoverLatch.countDown();
        }

        try {
            session.worker().execute(new Runnable() {
                @Override
                public void run() {
                    runExtraction(session, progress, discoverLatch, discoverRaw, pageUrl,
                            pageCookies, userAgent, maxWeek, nodes, manual, deep);
                }
            });
        } catch (RuntimeException exception) {
            // executor 已关闭（对话框在注入与提交之间被关掉）。
            session.close();
            progress.finish("提取已取消（对话框已关闭）", false);
            return;
        }

        // 看门狗只防真死锁：连续 WATCHDOG_IDLE_MS 没有任何活动才强制收尾。
        // 正常结束靠「自然跑完」或用户点「停止」，看门狗不参与正常流程。
        watchdog = new Thread(new Runnable() {
            @Override
            public void run() {
                while (!session.isClosed()) {
                    try {
                        Thread.sleep(2_000L);
                    } catch (InterruptedException interrupted) {
                        return;
                    }
                    if (session.isClosed() || !onlineExtractionState.isRunning()) return;
                    if (session.idleMillis() < OnlineExtractionSession.WATCHDOG_IDLE_MS) continue;
                    if (!onlineExtractionState.abortByWatchdog(System.currentTimeMillis())) return;
                    session.log("看门狗触发：连续 " + (OnlineExtractionSession.WATCHDOG_IDLE_MS / 1000)
                            + "s 无任何活动，判定后台卡死，强制收尾");
                    session.deadline.cancel();
                    session.close();
                    progress.finish("后台无响应，已强制结束并保留已有结果（"
                            + (OnlineExtractionSession.WATCHDOG_IDLE_MS / 1000) + "s 无活动）", false);
                    return;
                }
            }
        }, "online-extract-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
    }

    /**
     * 「停止」按钮：立即终止当前提取，<b>保留</b>已捕获与已解析的结果。
     *
     * <p>语义与实现：</p>
     * <ol>
     *   <li>状态机 {@code RUNNING → STOPPED}（幂等，重复点无效）；</li>
     *   <li>先置 JS 侧 abort 标志，让在途 fetch 在下一个检查点退出；</li>
     *   <li>{@code session.close()} 取消 deadline 并 shutdownNow 后台线程；</li>
     *   <li>{@link UiProgress#finish} 把已拿到的结果写回并恢复按钮——注意<b>不清空</b>
     *       {@code onlineDetails}，所以停止后仍可直接点「导入」。</li>
     * </ol>
     */
    private void stopOnlineExtraction(final TextView status) {
        if (!onlineParsing.get()) return;
        OnlineExtractionSession session = activeSession;
        long now = System.currentTimeMillis();
        if (!onlineExtractionState.stop(now)) return;
        onlineDiagnostics = onlineDiagnostics + "\n用户点击「停止」——保留已捕获结果\n";
        if (session != null) {
            session.log("用户点击「停止」：终止在途任务，保留已捕获/已解析结果");
            WebView view = currentWebView;
            if (view != null) {
                try {
                    view.evaluateJavascript(OnlineExtraction.abortAllScript(), null);
                } catch (RuntimeException ignored) {
                    // WebView 已不可用时忽略。
                }
            }
            session.close();
        }
        Thread watcher = watchdog;
        watchdog = null;
        if (watcher != null) watcher.interrupt();
        status.setText(onlineExtractionState.statusText(now));
        Button extractButton = onlineImportDialog == null ? null
                : (Button) onlineImportDialog.findViewById(R.id.button_online_extract);
        Button stopButton = onlineImportDialog == null ? null
                : (Button) onlineImportDialog.findViewById(R.id.button_online_stop);
        if (extractButton != null) {
            extractButton.setVisibility(View.VISIBLE);
            extractButton.setEnabled(true);
            extractButton.setText("提取课表");
        }
        if (stopButton != null) stopButton.setVisibility(View.GONE);
        Button importButton = onlineImportDialog == null ? null
                : (Button) onlineImportDialog.findViewById(R.id.button_online_import);
        if (importButton != null && !onlineDetails.isEmpty()) importButton.setEnabled(true);
        onlineParsing.set(false);
        onlineExtractionState.reset();
    }

    /**
     * 后台线程主体：发现 → 取数据 → 解析 → 展示。任何异常都在这里被兜住，
     * 保证 {@link #finishExtraction} 一定被调用一次。
     */
    private void runExtraction(OnlineExtractionSession session, UiProgress progress,
                               CountDownLatch discoverLatch, String[] discoverRaw,
                               String pageUrl, String pageCookies, String userAgent,
                               int maxWeek, int nodes, boolean manual, boolean deep) {
        OnlineImportLogic.Merged merged = new OnlineImportLogic.Merged();
        StringBuilder resultNote = new StringBuilder();
        String fatal = "";
        int acceptedCourses = 0;
        try {
            // ---------- 通道 1 ----------
            boolean discovered = session.await(discoverLatch, OnlineExtractionSession.DISCOVER_TIMEOUT_MS);
            String raw = discoverRaw[0];
            if (!discovered) {
                session.log("通道 1 超时：" + OnlineExtractionSession.DISCOVER_TIMEOUT_MS
                        + "ms 内未收到 discovery 回调（脚本可能被 CSP 拦截或主线程忙碌）");
            } else if (raw == null) {
                session.log("通道 1 返回 null：脚本未执行（页面跨域限制或 WebView 已导航）");
            } else if (raw.trim().equals("null")) {
                session.log("通道 1 返回 \"null\"：脚本抛出未捕获异常，被 WebView 转成 null");
            } else {
                session.log("通道 1 返回 " + raw.length() + " 字符");
            }
            OnlineExtraction.Discovery discovery = OnlineExtraction.parseDiscovery(raw);
            if (!discovery.error.isEmpty()) {
                session.log("通道 1 解析失败：" + discovery.error);
            }
            session.log("frame 树：" + discovery.frames.size() + " 个文档，可访问 "
                    + discovery.accessibleFrames().size() + " 个，跨域受阻 " + discovery.blockedFrames);
            for (OnlineExtraction.FrameNode frame : discovery.frames) {
                session.log("  ├ depth=" + frame.depth + (frame.name.isEmpty() ? "" : " name=" + frame.name)
                        + (frame.accessible ? "" : " [跨域]")
                        + " url=" + (frame.url.isEmpty() ? "(不可读)" : frame.url));
            }

            // 资源条目来自通道 1 脚本里的 performance.getEntriesByType('resource')，
            // 与 frame 文档 URL 一起进入候选池（frame 优先，因为课表本体通常就是 frame 的 src）。
            List<String> resources = discovery.resources;
            session.log("performance 资源候选：" + resources.size() + " 条（xhr/fetch）");
            List<String> candidates = OnlineExtraction.candidates(discovery, resources,
                    OnlineExtractionSession.MAX_CANDIDATES);
            session.log("候选 URL：" + candidates.size() + " 个");
            for (int i = 0; i < candidates.size(); i++) {
                session.log("  #" + (i + 1) + " " + candidates.get(i));
            }

            // ---------- 通道 0：被动捕获池（最高优先级）----------
            // POST 型教务系统（新版正方 jwglxt 等）的课表数据只存在于「用户点查询」那次
            // 真实响应里；主动 GET 通道拿不到，但被动捕获已经把它存下来了。
            session.deadline.enterPhase("被动捕获", System.currentTimeMillis());
            List<PassiveCapture.Captured> captured = onlineCapturePool.snapshot();
            session.log("被动捕获池：" + captured.size() + " 个响应（数据接口 "
                    + onlineCapturePool.dataLikeCount() + " 个）");
            int captureHits = 0;
            for (int i = 0; i < captured.size(); i++) {
                PassiveCapture.Captured item = captured.get(i);
                if (item.body.startsWith("[compressed")) continue;
                session.touch();
                TimetableParserRegistry.Outcome outcome = TimetableParserRegistry.parse(
                        new TimetableParserRegistry.Input(item.body, item.url, item.contentType,
                                maxWeek, nodes));
                session.log("捕获 #" + (i + 1) + " " + item.shortUrl()
                        + " [" + item.method + " " + item.status + " " + item.contentType + "]");
                session.log(TimetableParserRegistry.describe(outcome).trim());
                if (!outcome.isEmpty()) {
                    captureHits++;
                    merged = OnlineImportLogic.merge(merged.bases, merged.details,
                            outcome.result.bases, outcome.result.details);
                    acceptedCourses = merged.courseCount();
                } else {
                    // 解析失败的响应体进证据包——用户回传后即可补定点规则。
                    resultNote.append("未解析的捕获响应：").append(item.shortUrl())
                            .append(" [").append(item.contentType).append("]\n")
                            .append("  ").append(bodyPreview(item.body, 400)).append("\n\n");
                }
            }
            session.log("被动捕获命中 " + captureHits + " 个，累计 " + merged.courseCount()
                    + " 门课 / " + merged.detailCount() + " 条排课");
            progress.status("被动捕获命中 " + captureHits + " 个，继续扫描候选…");

            // ---------- 通道 2 ----------
            session.deadline.enterPhase(OnlineExtraction.ExtractionDeadline.PHASE_FETCH,
                    System.currentTimeMillis());
            List<OnlineImportLogic.ResourceResponse> bodies = new ArrayList<>();
            int proxyTried = 0;
            for (int i = 0; i < candidates.size(); i++) {
                String candidate = candidates.get(i);
                long now = System.currentTimeMillis();
                if (!session.deadline.canContinue(now)) {
                    session.log("预算耗尽，剩余 " + (candidates.size() - i) + " 个候选留待下次");
                    break;
                }
                progress.status("正在取数据 " + (i + 1) + "/" + candidates.size());
                String body = null;
                String source = "";
                if (proxyTried < OnlineExtractionSession.MAX_FETCH_PROXY_CANDIDATES) {
                    proxyTried++;
                    body = fetchViaProxy(session, candidate, pageUrl);
                    source = body != null ? "页内 fetch" : "页内 fetch 失败";
                }
                if (body == null || body.isEmpty()) {
                    // 双保险：fetch 代理失败（跨域/CSP/桥接不可用）时回落 Cookie 直取。
                    long left = session.deadline.remaining(System.currentTimeMillis());
                    if (left <= 0) {
                        session.log("  └ 预算耗尽，跳过直取");
                        continue;
                    }
                    int timeout = (int) Math.max(1500L, Math.min(OnlineExtractionSession.DIRECT_TIMEOUT_MS, left));
                    try {
                        OnlineImportLogic.ResourceResponse response =
                                fetchResource(candidate, pageUrl, userAgent, pageCookies, timeout);
                        if (response.body != null && !response.body.isEmpty()) {
                            body = response.body;
                            source += " → Cookie 直取(" + response.status + ")";
                        } else {
                            source += " → 直取空响应(" + response.status + ")";
                        }
                    } catch (IOException exception) {
                        source += " → 直取失败：" + exception.getMessage();
                    }
                }
                boolean ok = body != null && !body.isEmpty();
                session.deadline.noteCandidate(ok);
                session.log("  └ [" + (i + 1) + "] " + (ok ? "成功 " + body.length() + " 字符" : "失败")
                        + "（" + source + "）" + shortUrl(candidate));
                if (ok) {
                    bodies.add(new OnlineImportLogic.ResourceResponse(200, "", body, candidate));
                }
            }

            // ---------- 通道 3（可选）：深度扫描 ----------
            if (deep) {
                session.deadline.enterPhase("深度扫描", System.currentTimeMillis());
                progress.status("深度扫描中…");
                String deepRaw = runDeepScan(session);
                if (deepRaw != null) {
                    collectDeepResults(session, deepRaw, merged, maxWeek, nodes, resultNote);
                }
            }

            // ---------- ③ 解析合并 ----------
            session.deadline.enterPhase(OnlineExtraction.ExtractionDeadline.PHASE_PARSE,
                    System.currentTimeMillis());
            for (int i = 0; i < bodies.size(); i++) {
                OnlineImportLogic.ResourceResponse response = bodies.get(i);
                session.touch();
                // 统一走插件化注册表：新正方/Kingosoft/通用网格都参与，避免两套解析口径。
                TimetableParserRegistry.Outcome outcome = TimetableParserRegistry.parse(
                        new TimetableParserRegistry.Input(response.body, response.originUrl,
                                response.contentType, maxWeek, nodes));
                int before = merged.detailCount();
                if (!outcome.isEmpty()) {
                    merged = OnlineImportLogic.merge(merged.bases, merged.details,
                            outcome.result.bases, outcome.result.details);
                }
                session.log("解析 #" + (i + 1) + " " + shortUrl(response.originUrl)
                        + "：" + outcome.result.details.size() + " 条排课（"
                        + (outcome.winner.isEmpty() ? "无插件命中" : outcome.winner)
                        + "），合并新增 " + (merged.detailCount() - before));
                session.log(TimetableParserRegistry.describe(outcome).trim());
                if (outcome.isEmpty()) {
                    resultNote.append("未解析的候选响应：").append(shortUrl(response.originUrl))
                            .append("\n  ").append(bodyPreview(response.body, 400)).append("\n\n");
                } else {
                    acceptedCourses = merged.courseCount();
                }
            }

            if (merged.detailCount() == 0) {
                fatal = discovery.frames.isEmpty()
                        ? "未发现任何 frame 文档，请确认已打开课表页"
                        : "候选响应里没有识别到课表（已试 " + session.deadline.candidatesSeen()
                                + " 个），可长按「提取课表」开启深度扫描";
            }
        } catch (RuntimeException exception) {
            fatal = "提取异常：" + exception;
            session.log("异常：" + exception);
        } finally {
            finishExtraction(session, progress, merged, fatal, resultNote.toString(), manual);
        }
    }

    /**
     * 通道 2 主路径：页内 fetch 代理。
     *
     * <p>步骤：分配 requestId → 投递 {@link OnlineExtraction#fetchProxyScript} → 轮询
     * {@link OnlineExtraction.ChunkAssembler} 直到该 id 完成或超时。块本身由
     * {@link ChunkBridge} 从 WebView 线程投递到同一 executor，因此这里的轮询只是等待，
     * 拼装本身是串行安全的。</p>
     *
     * @return 响应体；失败返回 null（调用方回落直取）
     */
    private String fetchViaProxy(OnlineExtractionSession session, String url, String referer) {
        final String requestId = session.nextRequestId();
        final CountDownLatch submitted = new CountDownLatch(1);
        // 注入必须在 UI 线程：WebView 的所有方法都有线程亲和性。
        final String script = OnlineExtraction.fetchProxyScript(requestId, url);
        runOnUi(new Runnable() {
            @Override
            public void run() {
                WebView view = currentWebView;
                if (view == null) {
                    submitted.countDown();
                    return;
                }
                try {
                    view.evaluateJavascript(script, new ValueCallback<String>() {
                        @Override
                        public void onReceiveValue(String value) {
                            // fetch 代理不靠返回值传数据，返回值仅用于发现脚本自身的语法错误。
                            submitted.countDown();
                        }
                    });
                } catch (RuntimeException exception) {
                    submitted.countDown();
                }
            }
        });
        if (!session.await(submitted, 2_000)) {
            session.log("  ├ 代理脚本投递超时（UI 线程未响应）");
            return null;
        }
        long deadlineAt = System.currentTimeMillis() + OnlineExtractionSession.FETCH_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadlineAt) {
            if (session.isClosed()) return null;
            if (session.assembler.isDone(requestId)) {
                OnlineExtraction.Assembled result = session.assembler.result(requestId);
                if (result.complete()) {
                    if (result.truncated) session.log("  ├ 响应超过上限，已截断");
                    return result.bodyText();
                }
                session.log("  ├ 代理失败：" + result.error);
                return null;
            }
            if (!session.deadline.canContinue(System.currentTimeMillis())) {
                session.log("  ├ 总预算耗尽，放弃该候选");
                return null;
            }
            sleepQuietly(40);
        }
        session.log("  ├ 代理超时：" + OnlineExtractionSession.FETCH_TIMEOUT_MS + "ms 内未收齐");
        return null;
    }

    /**
     * 通道 3：深度扫描（几何 + DOM 表格）。保留旧脚本但<b>只在用户显式要求时</b>执行，
     * 并同样受 30s 总预算与 {@link #runOnUi} 的投递超时保护。
     */
    private String runDeepScan(OnlineExtractionSession session) {
        final CountDownLatch latch = new CountDownLatch(1);
        final String[] raw = new String[1];
        runOnUi(new Runnable() {
            @Override
            public void run() {
                WebView view = currentWebView;
                if (view == null) {
                    latch.countDown();
                    return;
                }
                try {
                    view.evaluateJavascript(OnlineImportLogic.extractionScript(), new ValueCallback<String>() {
                        @Override
                        public void onReceiveValue(String value) {
                            raw[0] = value;
                            latch.countDown();
                        }
                    });
                } catch (RuntimeException exception) {
                    latch.countDown();
                }
            }
        });
        if (!session.await(latch, 12_000)) {
            session.log("深度扫描超时或被取消");
            return null;
        }
        if (raw[0] == null || raw[0].trim().equals("null")) {
            session.log("深度扫描返回 null（脚本异常或页面跨域）");
            return null;
        }
        session.log("深度扫描返回 " + raw[0].length() + " 字符");
        return raw[0];
    }

    /** 深度扫描结果解析：DOM 表格 + div 格子 + 几何块，三路都并入 merged。 */
    private void collectDeepResults(OnlineExtractionSession session, String raw,
                                    OnlineImportLogic.Merged merged, int maxWeek, int nodes,
                                    StringBuilder note) {
        OnlineImportLogic.Capture capture = OnlineImportLogic.parseCapture(raw);
        if (capture == null) {
            session.log("深度扫描结果无法解析");
            return;
        }
        HtmlTimetableParser.Result tableResult = parseCapturedTables(capture.tables, maxWeek, nodes);
        HtmlTimetableParser.Result divResult = OnlineDivParser.parse(capture.cells, maxWeek, nodes);
        HtmlTimetableParser.Result geoResult = GeometryGridSolver.solve(
                capture.geometryCourses, capture.geometryDays, capture.geometryPeriods, maxWeek, nodes);
        OnlineImportLogic.Merged mergedTable = OnlineImportLogic.merge(merged.bases, merged.details,
                tableResult.bases, tableResult.details);
        OnlineImportLogic.Merged mergedDiv = OnlineImportLogic.merge(mergedTable.bases, mergedTable.details,
                divResult.bases, divResult.details);
        OnlineImportLogic.Merged mergedGeo = OnlineImportLogic.merge(mergedDiv.bases, mergedDiv.details,
                geoResult.bases, geoResult.details);
        merged.bases.clear();
        merged.bases.addAll(mergedGeo.bases);
        merged.details.clear();
        merged.details.addAll(mergedGeo.details);
        note.append("深度扫描：DOM 表格 ").append(tableResult.details.size())
                .append(" · div ").append(divResult.details.size())
                .append(" · 几何 ").append(geoResult.details.size()).append('\n');
        session.log("深度扫描解析：DOM " + tableResult.details.size() + " · div "
                + divResult.details.size() + " · 几何 " + geoResult.details.size());
    }

    /** 收尾：无论成功/失败/超时都走这里，保证按钮恢复且诊断可见。 */
    private void finishExtraction(OnlineExtractionSession session, UiProgress progress,
                                  OnlineImportLogic.Merged merged, String fatal, String note,
                                  boolean manual) {
        session.deadline.enterPhase(merged.detailCount() > 0
                ? OnlineExtraction.ExtractionDeadline.PHASE_DONE
                : OnlineExtraction.ExtractionDeadline.PHASE_TIMEOUT, System.currentTimeMillis());
        String summary = session.summary();
        session.log("── 汇总 ──");
        session.log(summary);
        session.log("课程 " + merged.courseCount() + " 门 / 排课 " + merged.detailCount() + " 条");
        if (!fatal.isEmpty()) session.log("原因：" + fatal);
        if (!note.isEmpty()) session.log(note.trim());
        final OnlineImportLogic.Merged result = merged;
        final String finalDiagnostics = session.diagnostics.text();
        final String finalSummary = summary;
        final String finalFatal = fatal;
        final boolean success = merged.detailCount() > 0;
        runOnUi(new Runnable() {
            @Override
            public void run() {
                onlineBases.clear();
                onlineBases.addAll(result.bases);
                onlineDetails.clear();
                onlineDetails.addAll(result.details);
                onlineDiagnostics = finalDiagnostics;
                progress.finish(finalSummary, finalFatal, success, result);
            }
        });
        session.close();
        if (watchdog != null && watchdog.isAlive()) watchdog.interrupt();
    }

    /**
     * UI 线程副作用的最小封装：状态栏文案、按钮恢复、诊断实时可见。
     *
     * <p>所有方法都先判 {@code onlineImportDialog} 是否还在，避免对话框关闭后操作已销毁的 View。</p>
     */
    private final class UiProgress {
        private final OnlineExtractionSession session;
        private final TextView status;
        private final Button extractButton;
        private final Button stopButton;
        private final AtomicBoolean finished = new AtomicBoolean(false);

        UiProgress(OnlineExtractionSession session, TextView status, Button extractButton,
                   Button stopButton) {
            this.session = session;
            this.status = status;
            this.extractButton = extractButton;
            this.stopButton = stopButton;
        }

        void status(final String text) {
            if (session.isClosed()) return;
            runOnUi(new Runnable() {
                @Override
                public void run() {
                    if (onlineImportDialog == null) return;
                    status.setText(text);
                }
            });
        }

        void finish(final String summary, final boolean success) {
            finish(summary, "", success, null);
        }

        void finish(final String summary, final String fatal, final boolean success,
                    final OnlineImportLogic.Merged merged) {
            // 幂等：正常收尾、超时看门狗、dismiss 三路都可能调用，只有第一次生效。
            if (!finished.compareAndSet(false, true)) return;
            runOnUi(new Runnable() {
                @Override
                public void run() {
                    if (onlineImportDialog == null) return;
                    // 按钮必定恢复：「停止」收起，「提取课表」在原位置回来。
                    if (stopButton != null) stopButton.setVisibility(View.GONE);
                    if (extractButton != null) {
                        extractButton.setVisibility(View.VISIBLE);
                        extractButton.setEnabled(true);
                        extractButton.setText("提取课表");
                    }
                    Button importButton = onlineImportDialog.findViewById(R.id.button_online_import);
                    boolean hasCourses = onlineDetails.size() > 0;
                    if (importButton != null && hasCourses) importButton.setEnabled(true);
                    String text = summary;
                    if (!hasCourses && !fatal.isEmpty()) text = summary + "；" + fatal;
                    status.setText(text);
                    if (success) {
                        Toast.makeText(activity, "已捕获 " + onlineDetails.size() + " 条排课",
                                Toast.LENGTH_SHORT).show();
                    }
                }
            });
            // 无论 UI 是否还在，解析标志都必须复位，否则再次点提取会被 CAS 拦住。
            onlineParsing.set(false);
            // 收尾后回到 IDLE，允许下一次提取。若收尾路径是「停止」或看门狗，
            // 它们已经各自推进过状态机并给了状态栏文案，这里的 reset 只负责放行下一轮。
            onlineExtractionState.reset();
        }
    }

    /** 在 UI 线程执行；若当前已在 UI 线程则直接跑，避免无谓投递。 */
    private void runOnUi(Runnable runnable) {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            runnable.run();
            return;
        }
        activity.runOnUiThread(runnable);
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /** 证据包里的正文预览：压成单行并截断，避免诊断被巨型 HTML 淹没。 */
    private static String bodyPreview(String body, int maxLength) {
        if (body == null) return "";
        String oneLine = body.replaceAll("[\\s\\u3000]+", " ").trim();
        if (oneLine.length() <= maxLength) return oneLine;
        return oneLine.substring(0, maxLength) + "…";
    }

    private static String shortUrl(String url) {
        if (url == null) return "";
        int query = url.indexOf('?');
        return query > 0 ? url.substring(0, query) : url;
    }

    private String parseFailureReason(HtmlTimetableParser.Result result) {
        if (result == null) return "解析器未返回结果";
        if (!result.errors.isEmpty()) return result.errors.get(0);
        if (!result.details.isEmpty()) return "解析 " + result.details.size() + " 条排课";
        if (!result.warnings.isEmpty()) return result.warnings.get(0);
        return "表头或单元格格式未被启发式识别，解析 0 条排课";
    }

    private HtmlTimetableParser.Result parseCapturedTables(List<String> tables, int maxWeek, int nodes) {
        StringBuilder html = new StringBuilder();
        if (tables != null) {
            for (String table : tables) html.append(table);
        }
        if (html.length() == 0) return new HtmlTimetableParser.Result();
        WakeupParserResult dedicated = parseWithDedicatedParser(html.toString());
        if (dedicated != null && !dedicated.isEmpty()) return mapped(dedicated);
        HtmlTimetableParser.Result result = HtmlTimetableParser.parse(html.toString(), maxWeek, nodes);
        if (dedicated != null) result.warnings.addAll(dedicated.warnings);
        return result;
    }

    private WakeupParserResult parseWithDedicatedParser(String source) {
        if (selectedSchool == null) return null;
        try {
            WakeupParser parser = WakeupParserFactory.create(
                    selectedSchool.type, selectedSchool.importType);
            return parser == null ? null : parser.parse(source);
        } catch (Exception exception) {
            WakeupParserResult result = new WakeupParserResult();
            result.warnings.add("专用 parser 失败：" + exception.getMessage());
            return result;
        }
    }

    private HtmlTimetableParser.Result mapped(WakeupParserResult dedicatedResult) {
        WakeupCourseMapper.Result mapped = WakeupCourseMapper.map(dedicatedResult.courses);
        HtmlTimetableParser.Result result = new HtmlTimetableParser.Result();
        result.bases.addAll(mapped.bases);
        result.details.addAll(mapped.details);
        result.warnings.addAll(dedicatedResult.warnings);
        return result;
    }

    /**
     * 在 UI 线程取一次页面 Cookie 字符串；失败一律返回空串，绝不抛给调用方。
     * 只做字符串快照，后台线程因此不必触碰 CookieManager。
     */
    private String cookieFor(String pageUrl) {
        if (pageUrl == null || pageUrl.isEmpty()) return "";
        try {
            String cookies = CookieManager.getInstance().getCookie(pageUrl);
            return cookies == null ? "" : cookies;
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    /**
     * 拉取一个 XHR 候选。
     *
     * @param cookies 调用方（UI 线程）预先用 CookieManager 取好的 Cookie 头。
     *                CookieManager 属于 WebView 家族，线程亲和性在部分 ROM 上不保证，
     *                因此不在后台线程直接访问；这里只吃一个纯字符串。
     * @param timeoutMillis 本次请求的连接/读取超时上限，由 FetchBudget 剩余额度夹取，
     *                      保证单轮总耗时不会超过预算。
     */
    private OnlineImportLogic.ResourceResponse fetchResource(String resourceUrl, String referer,
                                                              String userAgent, String cookies,
                                                              int timeoutMillis) throws IOException {
        HttpURLConnection connection = null;
        try {
            int timeout = Math.max(1000, timeoutMillis);
            URL url = new URL(resourceUrl);
            connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(timeout);
            connection.setReadTimeout(timeout);
            connection.setRequestMethod("GET");
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("User-Agent", userAgent == null || userAgent.isEmpty()
                    ? "Android WebView" : userAgent);
            connection.setRequestProperty("Referer", referer);
            connection.setRequestProperty("Accept", "text/html,application/json,*/*");
            connection.setRequestProperty("X-Requested-With", "XMLHttpRequest");
            if (cookies != null && !cookies.isEmpty()) {
                connection.setRequestProperty("Cookie", cookies);
            }
            int code = connection.getResponseCode();
            InputStream stream = code >= 200 && code < 300 ? connection.getInputStream()
                    : connection.getErrorStream();
            if ("gzip".equalsIgnoreCase(connection.getContentEncoding())) {
                stream = new GZIPInputStream(stream);
            }
            if (stream == null) {
                return new OnlineImportLogic.ResourceResponse(code, connection.getContentType(), "", resourceUrl);
            }
            try {
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int count;
                while ((count = stream.read(buffer)) >= 0 && output.size() < 2_000_000) {
                    output.write(buffer, 0, count);
                }
                return new OnlineImportLogic.ResourceResponse(code, connection.getContentType(),
                        HtmlTimetableParser.decode(output.toByteArray()), resourceUrl);
            } finally {
                stream.close();
            }
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private void showOnlineDiagnostics() {
        TextView text = new TextView(activity);
        text.setText(onlineDiagnostics);
        text.setTextIsSelectable(true);
        text.setTextSize(12);
        int padding = dp(16);
        text.setPadding(padding, padding, padding, padding);
        ScrollView scroll = new ScrollView(activity);
        scroll.addView(text);
        new AlertDialog.Builder(activity)
                .setTitle("诊断信息")
                .setView(scroll)
                .setPositiveButton("复制", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        ClipboardManager clipboard = (ClipboardManager)
                                activity.getSystemService(Context.CLIPBOARD_SERVICE);
                        if (clipboard != null) {
                            clipboard.setPrimaryClip(ClipData.newPlainText("教务导入诊断", onlineDiagnostics));
                            Toast.makeText(activity, "诊断信息已复制", Toast.LENGTH_SHORT).show();
                        }
                    }
                })
                .setNegativeButton("关闭", null)
                .show();
    }

    private void showSchoolPicker(final WebView webView, final EditText urlInput,
                                  final TextView status, final TextView selectedSchoolView,
                                  final Button schoolButton) {
        status.setText("正在读取学校清单...");
        new Thread(new Runnable() {
            @Override
            public void run() {
                String error = "";
                synchronized (schoolCatalog) {
                    if (schoolCatalog.isEmpty()) {
                        try {
                            schoolCatalog.addAll(SchoolInfoDecoder.parse(
                                    SchoolInfoDecoder.decode(readRawCatalog())));
                        } catch (Exception exception) {
                            error = "学校清单读取失败：" + exception.getMessage();
                        }
                    }
                }
                final String finalError = error;
                activity.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (!finalError.isEmpty()) {
                            status.setText(finalError);
                            return;
                        }
                        showSchoolPickerDialog(webView, urlInput, status, selectedSchoolView,
                                schoolButton);
                    }
                });
            }
        }).start();
    }

    private void showSchoolPickerDialog(final WebView webView, final EditText urlInput,
                                        final TextView status, final TextView selectedSchoolView,
                                        final Button schoolButton) {
        List<SchoolInfo> schools = limitedSchools(schoolCatalog, "");
        final List<SchoolInfo> filtered = new ArrayList<>(schools);
        TextView resultInfo = new TextView(activity);
        resultInfo.setTextColor(activity.getColor(R.color.textSecondary));
        resultInfo.setTextSize(12);
        resultInfo.setText("输入关键字搜索全量学校清单；当前显示前 " + filtered.size() + " 条");
        final ArrayAdapter<String> adapter = new ArrayAdapter<>(activity,
                android.R.layout.simple_list_item_1, schoolLabels(filtered));

        LinearLayout panel = new LinearLayout(activity);
        panel.setOrientation(LinearLayout.VERTICAL);
        int padding = dp(12);
        panel.setPadding(padding, padding, padding, 0);
        final EditText search = new EditText(activity);
        search.setSingleLine(true);
        search.setHint("搜索学校名称");
        search.setTextSize(13);
        ListView list = new ListView(activity);
        list.setAdapter(adapter);
        list.setOnItemClickListener(null);
        list.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        panel.addView(search, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        panel.addView(resultInfo, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        panel.addView(list, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(360)));

        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                filtered.clear();
                filtered.addAll(limitedSchools(schoolCatalog, s.toString()));
                adapter.clear();
                adapter.addAll(schoolLabels(filtered));
                adapter.notifyDataSetChanged();
                resultInfo.setText(s.toString().trim().isEmpty()
                        ? "当前显示前 " + filtered.size() + " 条，可输入学校名精确搜索"
                        : "匹配到 " + SchoolInfoDecoder.search(schoolCatalog, s.toString()).size()
                            + " 所学校，显示前 " + filtered.size() + " 条");
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });

        list.setOnItemClickListener(new android.widget.AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(android.widget.AdapterView<?> parent, View view,
                                    int position, long id) {
                if (position < 0 || position >= filtered.size()) return;
                selectedSchool = filtered.get(position);
                saveSelectedSchool(selectedSchool);
                updateSchoolViews(selectedSchoolView, schoolButton);
                updateOnlineFamilyGuide();
                if (!selectedSchool.url.trim().isEmpty()) urlInput.setText(selectedSchool.url);
                boolean dedicated = WakeupParserFactory.supports(
                        selectedSchool.type, selectedSchool.importType);
                status.setText(dedicated
                        ? "已选择 " + selectedSchool.name + "，将优先使用 " + parserName(selectedSchool) + " 解析"
                        : "已选择 " + selectedSchool.name + "，该格式暂未移植，将使用通用识别");
                if (!selectedSchool.url.trim().isEmpty()) loadOnlineUrl(webView, urlInput, status);
            }
        });

        new AlertDialog.Builder(activity)
                .setTitle("选择学校")
                .setView(panel)
                .setNegativeButton("取消", null)
                .show();
    }

    private String readRawCatalog() throws Exception {
        try (InputStream input = activity.getResources().openRawResource(R.raw.school_info_android_new);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) >= 0) output.write(buffer, 0, count);
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private List<SchoolInfo> orderedSchools(List<SchoolInfo> schools) {
        LinkedHashSet<String> recent = new LinkedHashSet<>(loadRecentSchoolNames());
        List<SchoolInfo> result = new ArrayList<>();
        List<SchoolInfo> others = new ArrayList<>();
        for (SchoolInfo school : schools) {
            if (recent.contains(school.name)) result.add(school);
            else others.add(school);
        }
        result.addAll(others);
        return result;
    }

    private List<String> schoolLabels(List<SchoolInfo> schools) {
        List<String> labels = new ArrayList<>();
        for (SchoolInfo school : schools) labels.add(school.label());
        return labels;
    }

    private String parserName(SchoolInfo school) {
        WakeupParser parser = WakeupParserFactory.create(school.type, school.importType);
        return parser == null ? "通用识别" : parser.name();
    }

    private List<SchoolInfo> limitedSchools(List<SchoolInfo> source, String query) {
        List<SchoolInfo> ordered = orderedSchools(SchoolInfoDecoder.search(source, query));
        return ordered.size() > 300 ? new ArrayList<>(ordered.subList(0, 300)) : ordered;
    }

    private void updateSchoolViews(TextView selectedSchoolView, Button schoolButton) {
        if (selectedSchool == null) {
            if (selectedSchoolView != null) selectedSchoolView.setText("未选择学校");
            if (schoolButton != null) schoolButton.setText("选择学校");
            return;
        }
        boolean dedicated = WakeupParserFactory.supports(selectedSchool.type, selectedSchool.importType);
        String suffix = dedicated ? parserName(selectedSchool) : "通用识别";
        if (selectedSchoolView != null) selectedSchoolView.setText(selectedSchool.name + " · " + suffix);
        if (schoolButton != null) schoolButton.setText("更换学校");
    }

    private SchoolInfo loadSavedSchool() {
        SharedPreferences preferences = onlinePreferences();
        String name = preferences.getString("school_name", "");
        if (name.trim().isEmpty()) return null;
        return SchoolInfo.of("", name, preferences.getString("school_url", ""),
                preferences.getString("school_type", ""), preferences.getString("school_import_type", ""),
                0, "");
    }

    private void saveSelectedSchool(SchoolInfo school) {
        SharedPreferences preferences = onlinePreferences();
        preferences.edit()
                .putString("school_name", school.name)
                .putString("school_type", school.type)
                .putString("school_import_type", school.importType)
                .putString("school_url", school.url)
                .putString("recent_schools", recentSchools(school.name))
                .apply();
    }

    private List<String> loadRecentSchoolNames() {
        List<String> names = new ArrayList<>();
        for (String item : onlinePreferences().getString("recent_schools", "").split("\n")) {
            String name = item.trim();
            if (!name.isEmpty() && !names.contains(name)) names.add(name);
        }
        return names;
    }

    private String recentSchools(String latest) {
        List<String> names = new ArrayList<>();
        names.add(latest);
        for (String name : loadRecentSchoolNames()) {
            if (!names.contains(name) && names.size() < 5) names.add(name);
        }
        return TextUtils.join("\n", names);
    }

    private SharedPreferences onlinePreferences() {
        return activity.getSharedPreferences("check-todo-online-import",
                android.content.Context.MODE_PRIVATE);
    }

    private int dp(int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }

    private void showOnlineImportPreview() {
        if (onlineDetails.isEmpty()) {
            Toast.makeText(activity, "尚未捕获到可导入课程", Toast.LENGTH_SHORT).show();
            return;
        }
        HtmlTimetableParser.Result result = new HtmlTimetableParser.Result();
        result.bases.addAll(onlineBases);
        result.details.addAll(onlineDetails);
        result.warnings.addAll(onlineWarnings);
        result.gridInfo = "教务系统抓取：共 " + onlineBases.size() + " 门课、"
                + onlineDetails.size() + " 条排课；已按课程/星期/节次/周次去重合并";
        new AlertDialog.Builder(activity)
                .setTitle("教务系统导入预览（" + result.details.size() + " 条排课）")
                .setMessage(buildHtmlPreview(result))
                .setNegativeButton("取消", null)
                .setPositiveButton("导入", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        importHtmlCourses(result);
                    }
                })
                .show();
    }

    private List<String> loadOnlineHistory() {
        SharedPreferences preferences = activity.getSharedPreferences("check-todo-online-import",
                android.content.Context.MODE_PRIVATE);
        List<String> urls = new ArrayList<>();
        for (String item : preferences.getString("url_history", "").split("\n")) {
            String url = OnlineImportLogic.normalizeUrl(item);
            if (!url.isEmpty() && !urls.contains(url)) urls.add(url);
        }
        return urls;
    }

    private void saveOnlineHistory(List<String> urls) {
        SharedPreferences preferences = activity.getSharedPreferences("check-todo-online-import",
                android.content.Context.MODE_PRIVATE);
        preferences.edit().putString("url_history", TextUtils.join("\n", urls)).apply();
    }

    public void handleCsvResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != REQUEST_CSV || resultCode != Activity.RESULT_OK || data == null) return;
        Uri uri = data.getData();
        if (uri == null) return;
        String csv = readText(uri);
        if (csv == null || csv.trim().isEmpty()) {
            showImportResult(0, "文件为空或无法读取", new ArrayList<String>());
            return;
        }
        TimetableCsvParser.Result result = TimetableCsvParser.parse(csv, repository.getConfig().maxWeek);
        if (!result.details.isEmpty()) {
            int maxNode = repository.getConfig().nodes;
            for (CourseDetail detail : result.details) maxNode = Math.max(maxNode, detail.endNode());
            TimetableConfig config = repository.getConfig();
            repository.saveConfig(new TimetableConfig(config.startDate, config.maxWeek,
                    Math.min(20, maxNode), config.showSat, config.showSun, config.showOtherWeekCourse));
            repository.importCourses(result.bases, result.details);
        }
        showImportResult(result.details.size(), result.errors.isEmpty() ? "" : result.errors.get(0),
                result.errors);
    }

    private void showImportResult(int success, String summary, List<String> errors) {
        String message = "成功导入 " + success + " 条排课";
        if (!errors.isEmpty()) message += "，失败 " + errors.size() + " 条";
        message += "\n\n请将 Excel 另存为 CSV 后再导入。";
        if (!errors.isEmpty()) {
            StringBuilder builder = new StringBuilder(message);
            builder.append("\n\n");
            int limit = Math.min(errors.size(), 10);
            for (int i = 0; i < limit; i++) builder.append(errors.get(i)).append('\n');
            if (errors.size() > limit) builder.append("...共 ").append(errors.size()).append(" 条错误");
            message = builder.toString();
        }
        new AlertDialog.Builder(activity)
                .setTitle("CSV 导入结果")
                .setMessage(message)
                .setPositiveButton("知道了", null)
                .show();
        refresh();
    }

    /** HTML 导入结果：先解析并弹预览，用户确认后才写库。 */
    public void handleHtmlResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != REQUEST_HTML || resultCode != Activity.RESULT_OK || data == null) return;
        Uri uri = data.getData();
        if (uri == null) return;
        byte[] bytes = readBytes(uri);
        if (bytes == null || bytes.length == 0) {
            showHtmlError("文件为空或无法读取");
            return;
        }
        String html = HtmlTimetableParser.decode(bytes);
        if (html.trim().isEmpty()) {
            showHtmlError("文件为空或无法读取");
            return;
        }
        // 页面里若带节次时间表（华农新正方把「1 / 08:00 / 08:40」写在节次列里），
        // 顺手抽出来，供预览页「应用时间表」按钮校准。抽取失败不影响导入。
        final java.util.List<TimeNode> pageNodes = ScauGridParser.parseTimeNodes(html);
        // 排课节次按当前时间表校验，超出范围的条目跳过并在预览中提醒。
        final HtmlTimetableParser.Result result = HtmlTimetableParser.parse(html,
                repository.getConfig().maxWeek, repository.getConfig().nodes);
        if (!result.errors.isEmpty()) {
            showHtmlError(result.errors.get(0));
            return;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(activity)
                .setTitle("HTML 导入预览（" + result.details.size() + " 条排课）")
                .setMessage(buildHtmlPreview(result))
                .setNegativeButton("取消", null)
                .setPositiveButton("导入", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        importHtmlCourses(result);
                    }
                });
        // 可选校准：页面自带节次时间时提供「应用时间表」，避免用户手动逐节修改。
        // 选择权交给用户——导入课表不该悄悄改掉已有的时间设置。
        if (pageNodes.size() >= 4) {
            builder.setNeutralButton("应用时间表", new android.content.DialogInterface.OnClickListener() {
                @Override
                public void onClick(android.content.DialogInterface dialog, int which) {
                    applyPageTimeNodes(pageNodes);
                }
            });
        }
        builder.show();
    }

    /**
     * 用页面上的节次时间校准本地时间表。
     *
     * <p>取舍：做成<b>用户显式点选</b>而不是自动应用。理由——导入课表与调整作息是两件事，
     * 自动改时间会让用户已有的手机闹钟/提醒口径在不知情的情况下漂移；而页面上读到的时间
     * 也不一定就是用户想要的（有的教务把夏季/冬季作息混在一起）。</p>
     */
    private void applyPageTimeNodes(java.util.List<TimeNode> pageNodes) {
        java.util.List<TimeNode> merged = new java.util.ArrayList<>(repository.getTimeNodes());
        int updated = 0;
        for (TimeNode page : pageNodes) {
            boolean replaced = false;
            for (int i = 0; i < merged.size(); i++) {
                if (merged.get(i).node == page.node) {
                    merged.set(i, page);
                    replaced = true;
                    updated++;
                    break;
                }
            }
            if (!replaced) {
                merged.add(page);
                updated++;
            }
        }
        java.util.Collections.sort(merged, new java.util.Comparator<TimeNode>() {
            @Override
            public int compare(TimeNode left, TimeNode right) {
                return Integer.compare(left.node, right.node);
            }
        });
        repository.saveTimeNodes(merged);
        Toast.makeText(activity, "已按页面校准 " + updated + " 个节次时间",
                Toast.LENGTH_SHORT).show();
    }

    private String buildHtmlPreview(HtmlTimetableParser.Result result) {
        StringBuilder builder = new StringBuilder(result.gridInfo);
        builder.append("\n\n");
        int limit = Math.min(result.details.size(), 20);
        for (int i = 0; i < limit; i++) {
            CourseDetail detail = result.details.get(i);
            builder.append("• ").append(baseName(result, detail))
                    .append("  ").append(DAY_LABELS[detail.day - 1])
                    .append(" ").append(detail.startNode).append("-").append(detail.endNode()).append("节")
                    .append(" ").append(detail.startWeek).append("-").append(detail.endWeek)
                    .append("周").append(typeLabel(detail.type));
            if (!detail.teacher.isEmpty()) builder.append("  ").append(detail.teacher);
            if (!detail.room.isEmpty()) builder.append("  ").append(detail.room);
            builder.append('\n');
        }
        if (result.details.size() > limit) {
            builder.append("...共 ").append(result.details.size()).append(" 条排课\n");
        }
        if (!result.warnings.isEmpty()) {
            builder.append("\n解析提醒（").append(result.warnings.size()).append(" 条）：\n");
            int warnLimit = Math.min(result.warnings.size(), 5);
            for (int i = 0; i < warnLimit; i++) {
                builder.append("· ").append(result.warnings.get(i)).append('\n');
            }
            if (result.warnings.size() > warnLimit) {
                builder.append("...共 ").append(result.warnings.size()).append(" 条\n");
            }
        }
        return builder.toString();
    }

    private void importHtmlCourses(HtmlTimetableParser.Result result) {
        TimetableConfig config = repository.getConfig();
        boolean needSat = config.showSat;
        boolean needSun = config.showSun;
        for (CourseDetail detail : result.details) {
            if (detail.day == 6) needSat = true;
            if (detail.day == 7) needSun = true;
        }
        if (needSat != config.showSat || needSun != config.showSun) {
            repository.saveConfig(new TimetableConfig(config.startDate, config.maxWeek,
                    config.nodes, needSat, needSun, config.showOtherWeekCourse));
        }
        repository.importCourses(result.bases, result.details);
        String message = "成功导入 " + result.details.size() + " 条排课";
        if (!result.warnings.isEmpty()) {
            message += "；另有 " + result.warnings.size() + " 条解析提醒";
        }
        new AlertDialog.Builder(activity)
                .setTitle("HTML 导入结果")
                .setMessage(message)
                .setPositiveButton("知道了", null)
                .show();
        refresh();
    }

    private void showHtmlError(String message) {
        new AlertDialog.Builder(activity)
                .setTitle("HTML 导入失败")
                .setMessage(message)
                .setPositiveButton("知道了", null)
                .show();
    }

    private static String baseName(HtmlTimetableParser.Result result, CourseDetail detail) {
        for (CourseBase base : result.bases) {
            if (base.id.equals(detail.baseId)) return base.name;
        }
        return "";
    }

    private String readText(Uri uri) {
        StringBuilder builder = new StringBuilder();
        try (InputStream stream = activity.getContentResolver().openInputStream(uri);
             BufferedReader reader = new BufferedReader(new InputStreamReader(stream))) {
            String line;
            while ((line = reader.readLine()) != null) {
                builder.append(line).append('\n');
            }
        } catch (Exception ignored) {
            return null;
        }
        return builder.toString();
    }

    /** 按字节读取：编码检测交给 HtmlTimetableParser.decode。 */
    private byte[] readBytes(Uri uri) {
        try (InputStream stream = activity.getContentResolver().openInputStream(uri);
             ByteArrayOutputStream buffer = new ByteArrayOutputStream()) {
            byte[] chunk = new byte[8192];
            int read;
            while ((read = stream.read(chunk)) > 0) buffer.write(chunk, 0, read);
            return buffer.toByteArray();
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String typeLabel(int type) {
        if (type == CourseDetail.TYPE_ODD) return TYPE_LABELS[1];
        if (type == CourseDetail.TYPE_EVEN) return TYPE_LABELS[2];
        return TYPE_LABELS[0];
    }

    private static int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value.trim());
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static double parseDouble(String value, double fallback) {
        try {
            return Double.parseDouble(value.trim());
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static int columns(TimetableConfig config) {
        int columns = 5;
        if (config.showSat) columns++;
        if (config.showSun) columns++;
        return Math.min(7, columns);
    }

    private float dp(float value) {
        return value * activity.getResources().getDisplayMetrics().density;
    }
}
