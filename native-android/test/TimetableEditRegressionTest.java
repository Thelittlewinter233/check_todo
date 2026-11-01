package test;

import com.checktodo.app.domain.CourseBase;
import com.checktodo.app.domain.CourseDetail;
import com.checktodo.app.domain.TimeNode;
import com.checktodo.app.domain.TimetableConfig;
import com.checktodo.app.domain.TimetableEditor;
import com.checktodo.app.domain.TimetableSheet;

import java.util.ArrayList;
import java.util.List;

/**
 * 复现真机问题：教务导入课表后，手动「添加课程」把导入的课程全部刷掉。
 *
 * <p>根因（TimetableRepository.saveCourse）：构建新详情集合时只放了「本次这一条」，
 * 既没有带上已导入的详情，也没有在新课 baseId 由仓库重新生成时把新详情指向新 id。
 * 于是保存后 bases 仍在、details 被清空 → 界面上所有课程消失。</p>
 *
 * <p>修复口径（本测试即规格）：编辑/新增一门课只能改动这一门，其余课程与排课必须原样保留；
 * 新课程（baseId 为空）由仓库生成 id 后，新详情必须绑定到该 id。</p>
 */
public final class TimetableEditRegressionTest {
    private static int passed;
    private static int failed;

    public static void main(String[] args) {
        testManualAddKeepsImported();
        testImportThenManualAdd();
        testEditInPlaceKeepsOthers();
        testNewBaseDetailRebindsId();
        testRemoveDetailOnlyDropsItsBase();
        System.out.println("passed=" + passed + " failed=" + failed);
        if (failed > 0) System.exit(1);
    }

    /** 导入 N 门 → 手动新增 1 门 → N+1 门全在，且新课程的详情不再丢。 */
    private static void testManualAddKeepsImported() {
        TimetableSheet imported = importedSheet(3);
        CourseBase manual = new CourseBase("", "体育", 0, "", 1);
        CourseDetail manualDetail = new CourseDetail("", "", 3, 5, 2,
                1, 16, CourseDetail.TYPE_ALL, "操场", "王老师", 0);

        TimetableSheet next = TimetableEditor.saveCourse(imported, manual, manualDetail, "new-base-id",
                new ArrayList<String>());
        String newBaseId = newBaseId(next, "体育");

        check("新增后课程数 = 导入 3 + 新增 1", next.bases.size() == 4);
        check("导入的 3 门课程名都还在",
                hasBase(next, "课程A") && hasBase(next, "课程B") && hasBase(next, "课程C"));
        check("导入的 3 条排课都还在", countDetailsOf(next, "base-a") == 1
                && countDetailsOf(next, "base-b") == 1 && countDetailsOf(next, "base-c") == 1);
        check("导入课程排课的教室原样保留",
                roomOf(next, "base-a").equals("A-101") && roomOf(next, "base-c").equals("C-103"));
        check("新增课程有自己的排课", newBaseId != null && countDetailsOf(next, newBaseId) == 1);
        check("新课排课绑定到生成的 baseId",
                newBaseId != null && "操场".equals(roomOf(next, newBaseId)));
        check("总排课数 = 3 + 1", next.details.size() == 4);
        check("生成期间没有残留空 baseId 详情", !hasEmptyBaseDetail(next));
        check("原表不被就地修改（不可变）", imported.bases.size() == 3 && imported.details.size() == 3);
    }

    /** 走真实导入链路：导入 → 手动新增，链路上任何一步都不能清空另一部分。 */
    private static void testImportThenManualAdd() {
        TimetableSheet empty = importedSheet(0);
        List<CourseBase> bases = new ArrayList<>();
        List<CourseDetail> details = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            String upper = String.valueOf((char) ('A' + i));
            bases.add(new CourseBase("imp-" + i, "导入课" + upper, 0, "", 2));
            details.add(new CourseDetail("imp-d-" + i, "imp-" + i, 1, i + 1, 1,
                    1, 16, CourseDetail.TYPE_ALL, "教室" + i, "", 0));
        }
        TimetableSheet imported = TimetableEditor.importCourses(empty, bases, details);
        check("导入 5 门课", imported.bases.size() == 5 && imported.details.size() == 5);

        TimetableSheet next = TimetableEditor.saveCourse(imported,
                new CourseBase("", "手动手动", 0, "", 0),
                new CourseDetail("", "", 7, 3, 2, 1, 16, CourseDetail.TYPE_ALL, "", "", 0),
                "manual-1", new ArrayList<String>());
        check("导入后再手动新增：6 门全在", next.bases.size() == 6);
        check("导入的 5 条排课一条不少", next.details.size() == 6
                && countDetailsOf(next, "manual-1") == 1);
        int importedSurvivors = 0;
        for (CourseDetail detail : next.details) {
            if (!detail.baseId.equals("manual-1")) importedSurvivors++;
        }
        check("导入排课未被覆盖", importedSurvivors == 5);
    }

    /** 编辑已有课程（改教室/节次）：只动这一门，其余不动。 */
    private static void testEditInPlaceKeepsOthers() {
        TimetableSheet imported = importedSheet(3);
        CourseBase edited = new CourseBase("base-b", "课程B改", 4, "备注", 3);
        CourseDetail editedDetail = new CourseDetail("detail-b", "base-b", 4, 2, 2,
                1, 16, CourseDetail.TYPE_ALL, "新教室", "新老师", 0);

        TimetableSheet next = TimetableEditor.saveCourse(imported, edited, editedDetail, "ignored",
                new ArrayList<String>());

        check("编辑后课程数不变", next.bases.size() == 3);
        check("编辑的课程名已更新", hasBase(next, "课程B改") && !hasBase(next, "课程B"));
        check("编辑后总排课数不变", next.details.size() == 3);
        check("编辑保留课程原色（换名不换色）", colorOf(next, "base-b") == 2);
        check("编辑的排课已更新", "新教室".equals(roomOf(next, "base-b")));
        check("编辑的排课 id 保持不变",
                detailOf(next, "base-b") != null && "detail-b".equals(detailOf(next, "base-b").id));
        check("其余课程排课未受影响",
                "A-101".equals(roomOf(next, "base-a"))
                        && "C-103".equals(roomOf(next, "base-c")));
    }

    /** 新课程只给了一条详情：仓库必须把占位 baseId 换成生成的 id。 */
    private static void testNewBaseDetailRebindsId() {
        TimetableSheet imported = importedSheet(1);
        CourseDetail placeholder = new CourseDetail("", "", 1, 1, 2, 1, 16,
                CourseDetail.TYPE_ALL, "R", "T", 0);
        List<String> used = new ArrayList<>();
        TimetableSheet next = TimetableEditor.saveCourse(imported,
                new CourseBase("", "新课", 0, "", 0), placeholder, "generated-id", used);
        CourseDetail detail = detailOf(next, "generated-id");
        check("新详情绑定到生成的 baseId", detail != null);
        check("新详情位置/教室保留", "R".equals(roomOf(next, "generated-id")));
        check("没有指向空 baseId 的孤儿详情", !hasEmptyBaseDetail(next));
        check("每次生成都消耗一个新 id 并登记已用", used.size() == 1
                && !used.get(0).isEmpty() && !used.get(0).equals("generated-id"));
    }

    /** 删除课程传入 detail=null：只删该课的排课，课程主数据与其余课程都保留。 */
    private static void testRemoveDetailOnlyDropsItsBase() {
        TimetableSheet imported = importedSheet(2);
        TimetableSheet next = TimetableEditor.saveCourse(imported,
                new CourseBase("base-a", "课程A", 1, "", 0), null, "ignored", new ArrayList<String>());
        check("删除排课不删课程主数据", next.bases.size() == 2 && hasBase(next, "课程A"));
        check("删除后只删该课排课", next.details.size() == 1
                && detailOf(next, "base-b") != null && detailOf(next, "base-a") == null);
    }

    // ---- helpers ----

    private static TimetableSheet importedSheet(int count) {
        List<TimeNode> nodes = new ArrayList<>();
        for (int i = 1; i <= 12; i++) nodes.add(new TimeNode(i, "08:00", "08:45"));
        List<CourseBase> bases = new ArrayList<>();
        List<CourseDetail> details = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String letter = String.valueOf((char) ('a' + i));
            String upper = letter.toUpperCase();
            bases.add(new CourseBase("base-" + letter, "课程" + upper, i + 1, "", 2));
            details.add(new CourseDetail("detail-" + letter, "base-" + letter, i + 1, 1, 2,
                    1, 16, CourseDetail.TYPE_ALL, upper + "-10" + (i + 1), "老师" + upper, 0));
        }
        return new TimetableSheet("sheet-1", "导入课表",
                new TimetableConfig("2026-09-07", 20, 12, false, false, true),
                nodes, bases, details);
    }

    private static boolean hasBase(TimetableSheet sheet, String name) {
        for (CourseBase base : sheet.bases) {
            if (base.name.equals(name)) return true;
        }
        return false;
    }

    private static String newBaseId(TimetableSheet sheet, String name) {
        for (CourseBase base : sheet.bases) {
            if (base.name.equals(name)) return base.id;
        }
        return null;
    }

    private static int colorOf(TimetableSheet sheet, String baseId) {
        for (CourseBase base : sheet.bases) {
            if (base.id.equals(baseId)) return base.color;
        }
        return -1;
    }

    private static String roomOf(TimetableSheet sheet, String baseId) {
        CourseDetail detail = detailOf(sheet, baseId);
        return detail == null ? "<缺失>" : detail.room;
    }

    private static CourseDetail detailOf(TimetableSheet sheet, String baseId) {
        for (CourseDetail detail : sheet.details) {
            if (detail.baseId.equals(baseId)) return detail;
        }
        return null;
    }

    private static int countDetailsOf(TimetableSheet sheet, String baseId) {
        int n = 0;
        for (CourseDetail detail : sheet.details) {
            if (detail.baseId.equals(baseId)) n++;
        }
        return n;
    }

    private static boolean hasEmptyBaseDetail(TimetableSheet sheet) {
        for (CourseDetail detail : sheet.details) {
            if (detail.baseId == null || detail.baseId.trim().isEmpty()) return true;
        }
        return false;
    }

    private static void check(String name, boolean condition) {
        if (condition) {
            passed++;
            System.out.println("PASS " + name);
        } else {
            failed++;
            System.out.println("FAIL " + name);
        }
    }
}
