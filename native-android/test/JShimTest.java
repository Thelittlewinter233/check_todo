package test;

import com.checktodo.app.jshim.Document;
import com.checktodo.app.jshim.Element;
import com.checktodo.app.jshim.Elements;
import com.checktodo.app.jshim.Jsoup;

/** Selector and tree-builder tests for the zero-dependency Jsoup shim. */
public final class JShimTest {
    private static int passed;
    private static int failed;

    public static void main(String[] args) {
        testTreeAndText();
        testTagIdClass();
        testAttributes();
        testCombinators();
        testPseudos();
        testRawTextAndData();

        System.out.println("passed=" + passed + " failed=" + failed);
        if (failed > 0) System.exit(1);
    }

    private static void testTreeAndText() {
        Document document = Jsoup.parse("<DIV ID='a'><b>Alpha</b>Beta&nbsp;&amp;&nbsp;<i>Gamma</i></DIV>");
        check("unclosed root tolerated", document.select("div#a").size() == 1);
        check("text is decoded and normalized", document.selectFirst("div").text().equals("Alpha Beta & Gamma"));
        check("ownText excludes descendants", document.selectFirst("div").ownText().equals("Beta &"));
    }

    private static void testTagIdClass() {
        Document document = Jsoup.parse("<table><tr><td>one</td></tr><tr><td>two</td></tr></table>"
                + "<ul class='courseInfo list'><li class='kbcontent'>math</li></ul><span id='kb'>math</span>");
        check("tag selector", document.select("td").size() == 2);
        check("id selector", document.select("#kb").text().equals("math"));
        check("class selector", document.select(".kbcontent").text().equals("math"));
        check("comma selector", document.select("span, .kbcontent").size() == 2);
    }

    private static void testAttributes() {
        Document document = Jsoup.parse(
                "<table border=1 width=98% class='scheduleTable table table-bordered table-hover'>"
                        + "<tr style='color:red'><td data-role='item' lesson>cell</td>"
                        + "<td _nk=\"x\" title=\"start\">value</td></tr></table>");
        check("unquoted attribute", document.select("table[border=1]").size() == 1);
        check("quoted attribute", document.select("table[width='98%']").size() == 1);
        check("multi-word class attribute", document.select(
                "table[class=scheduleTable table table-bordered table-hover]").size() == 1);
        check("presence selector", document.select("[lesson]").size() == 1);
        check("prefix selector", document.select("[data-role^=item]").size() == 1);
        check("contains selector", document.select("[title*=tar]").size() == 1);
        check("quoted value with space", document.select("[_nk=\"x\"]").size() == 1);
    }

    private static void testCombinators() {
        Document document = Jsoup.parse("<div class='address'><p class='content'>A</p><div><p class='content'>B</p></div></div>"
                + "<section><p class='content'>C</p></section>");
        check("descendant selector", document.select("div .content").size() == 2);
        check("child selector", document.select("div > p.content").size() == 2);
        check("child excludes deeper descendants", document.select("div.address > p.content").text().equals("A"));
        check("selector excludes root", document.select("div").size() == 2);
    }

    private static void testPseudos() {
        Document document = Jsoup.parse("<table><tbody>"
                + "<tr><th>head</th></tr><tr><td>1</td></tr><tr><td>2</td></tr>"
                + "<tr><td>3</td></tr></tbody></table>"
                + "<span class='x'>math</span><span>plain</span><p id='own'>2</p>");
        check("gt selector", document.select("tr:gt(0)").size() == 3);
        check("not selector", document.select("span:not([class])").text().equals("plain"));
        check("contains selector", document.select("td:contains(2)").size() == 1);
        check("containsOwn selector", document.select("p:containsOwn(2)").size() == 1);
        Elements rows = document.select("tbody tr");
        check("elements helper", rows.size() == 4 && rows.get(0).select("th").size() == 1);
    }

    private static void testRawTextAndData() {
        Document document = Jsoup.parse("<head><style>.x{color:red}</style>"
                + "<script>var table='<table><tr><td>x</td></tr></table>';</script></head>");
        Element style = document.selectFirst("style");
        Element script = document.selectFirst("script");
        check("style is not parsed as markup", style.data().contains(".x{color:red}"));
        check("script raw data is preserved", script.data().contains("<table>"));
        check("script content is not selectable", document.select("td").isEmpty());
    }

    private static void check(String label, boolean condition) {
        if (condition) {
            passed++;
            System.out.println("PASS " + label);
        } else {
            failed++;
            System.out.println("FAIL " + label);
        }
    }
}
