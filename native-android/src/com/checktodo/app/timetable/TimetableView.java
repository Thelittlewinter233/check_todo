package com.checktodo.app.timetable;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import com.checktodo.app.R;
import com.checktodo.app.domain.CourseBase;
import com.checktodo.app.domain.CourseDetail;
import com.checktodo.app.domain.TimeNode;
import com.checktodo.app.domain.TimetableConfig;
import com.checktodo.app.domain.TimetableLogic;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 自绘周课表网格：左侧节次时间轴 + 5-7 天课程区，支持跨节与重叠课程分栏。 */
public final class TimetableView extends View {
    public interface CourseClickListener {
        void onCourseClick(CourseDetail detail);
    }

    private static final class HitTarget {
        final RectF rect = new RectF();
        final CourseDetail detail;

        HitTarget(CourseDetail detail) {
            this.detail = detail;
        }
    }

    private final float density;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint textPaint = new TextPaint(TextPaint.ANTI_ALIAS_FLAG);
    private final List<HitTarget> targets = new ArrayList<>();
    private final int[] palette = {
            R.color.courseColor1, R.color.courseColor2, R.color.courseColor3,
            R.color.courseColor4, R.color.courseColor5, R.color.courseColor6,
            R.color.courseColor7, R.color.courseColor8, R.color.courseColor9
    };

    private TimetableConfig config;
    private List<CourseBase> bases = Collections.emptyList();
    private List<CourseDetail> details = Collections.emptyList();
    private List<TimeNode> timeNodes = Collections.emptyList();
    private Map<String, CourseBase> baseMap = Collections.emptyMap();
    private int selectedWeek = 1;
    private Date now = new Date();
    private CourseClickListener listener;

    public TimetableView(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;
    }

    public void setTimetable(TimetableConfig config, List<CourseBase> bases,
                             List<CourseDetail> details, List<TimeNode> timeNodes,
                             int selectedWeek, Date now) {
        this.config = config;
        this.bases = bases;
        this.details = details;
        this.timeNodes = timeNodes;
        this.selectedWeek = selectedWeek;
        this.now = now == null ? new Date() : now;
        java.util.LinkedHashMap<String, CourseBase> map = new java.util.LinkedHashMap<>();
        for (CourseBase base : bases) map.put(base.id, base);
        this.baseMap = map;
        requestLayout();
        invalidate();
    }

    public void setCourseClickListener(CourseClickListener listener) {
        this.listener = listener;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int nodeCount = config == null ? 12 : Math.max(1, config.nodes);
        int height = (int) (padding(10) + nodeCount * nodeHeight() + padding(14));
        setMeasuredDimension(width, height);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        targets.clear();
        if (config == null) return;
        int width = getWidth();
        int height = getHeight();
        float top = padding(10);
        int columns = columns();
        float timeWidth = dp(46);
        float dayWidth = (width - timeWidth) / columns;

        paint.setColor(getContext().getColor(R.color.cardBackground));
        paint.setStyle(Paint.Style.FILL);
        canvas.drawRoundRect(new RectF(0, 0, width, height), dp(6), dp(6), paint);

        drawTimeAxis(canvas, top, timeWidth);
        drawGrid(canvas, top, timeWidth, dayWidth, columns);
        drawCourses(canvas, top, timeWidth, dayWidth, columns);
    }

    private void drawTimeAxis(Canvas canvas, float top, float timeWidth) {
        float nodeHeight = nodeHeight();
        paint.setColor(getContext().getColor(R.color.textSecondary));
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1));
        canvas.drawLine(timeWidth, top, timeWidth, top + config.nodes * nodeHeight, paint);

        textPaint.setColor(getContext().getColor(R.color.textSecondary));
        textPaint.setTextSize(sp(9));
        textPaint.setTextAlign(Paint.Align.CENTER);
        for (int node = 1; node <= config.nodes; node++) {
            TimeNode timeNode = findTimeNode(node);
            if (timeNode == null) continue;
            float center = top + (node - 1) * nodeHeight + nodeHeight / 2f;
            String startTime = timeNode.startTime;
            String endTime = timeNode.endTime;
            canvas.drawText(ellipsize(startTime, timeWidth - dp(4)), timeWidth / 2f,
                    center - nodeHeight * 0.16f, textPaint);
            canvas.drawText(ellipsize(endTime, timeWidth - dp(4)), timeWidth / 2f,
                    center + nodeHeight * 0.28f, textPaint);
            canvas.drawLine(0, top + node * nodeHeight, timeWidth, top + node * nodeHeight, paint);
        }
    }

    private void drawGrid(Canvas canvas, float top, float timeWidth, float dayWidth, int columns) {
        float nodeHeight = nodeHeight();
        int today = TimetableLogic.dayOfWeek(now);
        int currentWeek = TimetableLogic.currentWeek(config, now);
        boolean sameWeek = selectedWeek == currentWeek;
        int currentDay = sameWeek ? today : 0;

        paint.setStyle(Paint.Style.FILL);
        for (int day = 1; day <= columns; day++) {
            if (day == currentDay) {
                paint.setColor(getContext().getColor(R.color.statusActiveBackground));
                paint.setAlpha(45);
                canvas.drawRect(timeWidth + (day - 1) * dayWidth, top,
                        timeWidth + day * dayWidth, top + config.nodes * nodeHeight, paint);
                paint.setAlpha(255);
            }
        }

        paint.setColor(getContext().getColor(R.color.calendarOffStroke));
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1));
        for (int node = 0; node <= config.nodes; node++) {
            canvas.drawLine(timeWidth, top + node * nodeHeight, getWidth(), top + node * nodeHeight, paint);
        }
        for (int day = 0; day <= columns; day++) {
            canvas.drawLine(timeWidth + day * dayWidth, top, timeWidth + day * dayWidth,
                    top + config.nodes * nodeHeight, paint);
        }
    }

    private void drawCourses(Canvas canvas, float top, float timeWidth, float dayWidth, int columns) {
        float nodeHeight = nodeHeight();
        for (int day = 1; day <= columns; day++) {
            drawDayCourses(canvas, day, top, timeWidth, dayWidth, nodeHeight);
        }
    }

    private void drawDayCourses(Canvas canvas, int day, float top, float timeWidth,
                                float dayWidth, float nodeHeight) {
        List<CourseDetail> active = new ArrayList<>();
        List<CourseDetail> otherWeek = new ArrayList<>();
        for (CourseDetail detail : details) {
            if (detail.day != day) continue;
            if (TimetableLogic.isCourseInWeek(detail, selectedWeek)) active.add(detail);
            else if (config.showOtherWeekCourse) otherWeek.add(detail);
        }
        Collections.sort(active, (left, right) -> {
            int byStart = Integer.compare(left.startNode, right.startNode);
            if (byStart != 0) return byStart;
            int byStep = Integer.compare(right.step, left.step);
            if (byStep != 0) return byStep;
            return left.baseId.compareTo(right.baseId);
        });

        for (CourseDetail detail : otherWeek) {
            drawCourse(canvas, detail, top, timeWidth + (day - 1) * dayWidth, dayWidth,
                    nodeHeight, 0.24f);
        }

        List<List<CourseDetail>> groups = groupOverlaps(active);
        for (List<CourseDetail> group : groups) {
            int count = Math.max(1, group.size());
            float slotWidth = (dayWidth - padding(6)) / count;
            for (int index = 0; index < group.size(); index++) {
                CourseDetail detail = group.get(index);
                float left = timeWidth + (day - 1) * dayWidth + padding(3) + index * slotWidth;
                drawCourse(canvas, detail, top, left, slotWidth, nodeHeight, 1f);
            }
        }
    }

    private List<List<CourseDetail>> groupOverlaps(List<CourseDetail> details) {
        List<List<CourseDetail>> groups = new ArrayList<>();
        int currentEnd = -1;
        List<CourseDetail> currentGroup = null;
        for (CourseDetail detail : details) {
            if (currentGroup == null || detail.startNode > currentEnd) {
                currentGroup = new ArrayList<>();
                groups.add(currentGroup);
                currentEnd = detail.endNode();
            } else {
                currentEnd = Math.max(currentEnd, detail.endNode());
            }
            currentGroup.add(detail);
        }
        return groups;
    }

    private void drawCourse(Canvas canvas, CourseDetail detail, float top, float left,
                            float width, float nodeHeight, float alpha) {
        if (detail.startNode < 1 || detail.startNode > config.nodes) return;
        int endNode = Math.min(detail.endNode(), config.nodes);
        float blockTop = top + (detail.startNode - 1) * nodeHeight + dp(2);
        float blockBottom = top + endNode * nodeHeight - dp(2);
        float blockLeft = left + dp(2);
        float blockRight = left + width - dp(2);
        if (blockRight <= blockLeft || blockBottom <= blockTop) return;

        CourseBase base = baseMap.get(detail.baseId);
        String name = base == null ? "未命名课程" : base.name;
        int colorIndex = base == null || base.color <= 0
                ? Math.abs(baseIdHash(detail.baseId)) % palette.length
                : (base.color - 1) % palette.length;
        int color = getContext().getColor(palette[colorIndex]);

        paint.setColor(color);
        paint.setAlpha((int) (255 * alpha));
        paint.setStyle(Paint.Style.FILL);
        RectF rect = new RectF(blockLeft, blockTop, blockRight, blockBottom);
        canvas.drawRoundRect(rect, dp(5), dp(5), paint);
        paint.setAlpha(255);

        paint.setColor(getContext().getColor(R.color.cardBackground));
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1));
        canvas.drawRoundRect(rect, dp(5), dp(5), paint);

        textPaint.setColor(getContext().getColor(R.color.onPrimary));
        textPaint.setAlpha((int) (255 * alpha));
        textPaint.setTextSize(sp(10));
        textPaint.setTextAlign(Paint.Align.CENTER);
        String nameText = ellipsize(name, width - dp(6));
        float nameY = blockTop + dp(14);
        canvas.drawText(nameText, blockLeft + width / 2f, nameY, textPaint);

        if (blockBottom - blockTop > dp(44)) {
            textPaint.setTextSize(sp(9));
            String roomText = ellipsize(detail.room, width - dp(6));
            canvas.drawText(roomText, blockLeft + width / 2f, blockTop + dp(28), textPaint);
        }
        if (blockBottom - blockTop > dp(64) && !detail.teacher.isEmpty()) {
            textPaint.setTextSize(sp(9));
            String teacherText = ellipsize(detail.teacher, width - dp(6));
            canvas.drawText(teacherText, blockLeft + width / 2f, blockTop + dp(41), textPaint);
        }
        textPaint.setAlpha(255);

        HitTarget target = new HitTarget(detail);
        target.rect.set(rect);
        targets.add(target);
    }

    private TimeNode findTimeNode(int node) {
        for (TimeNode timeNode : timeNodes) {
            if (timeNode.node == node) return timeNode;
        }
        return null;
    }

    private int columns() {
        if (config == null) return 7;
        int columns = 5;
        if (config.showSat) columns++;
        if (config.showSun) columns++;
        return Math.min(7, columns);
    }

    private float nodeHeight() {
        return dp(54);
    }

    private int baseIdHash(String id) {
        return id == null ? 0 : id.hashCode();
    }

    private String ellipsize(String text, float maxWidth) {
        if (text == null) return "";
        if (textPaint.measureText(text) <= maxWidth) return text;
        CharSequence result = TextUtils.ellipsize(text, textPaint, maxWidth, TextUtils.TruncateAt.END);
        return result.toString();
    }

    private float dp(float value) {
        return value * density;
    }

    private float sp(float value) {
        return value * getResources().getDisplayMetrics().scaledDensity;
    }

    private float padding(float value) {
        return dp(value);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getAction() != MotionEvent.ACTION_UP) return true;
        float x = event.getX();
        float y = event.getY();
        for (HitTarget target : targets) {
            if (target.rect.contains(x, y) && listener != null) {
                listener.onCourseClick(target.detail);
                return true;
            }
        }
        return true;
    }
}
