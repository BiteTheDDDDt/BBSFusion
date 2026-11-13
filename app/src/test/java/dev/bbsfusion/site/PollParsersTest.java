package dev.bbsfusion.site;

import dev.bbsfusion.core.Poll;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

public final class PollParsersTest {
    @Test
    public void s1MobilePollKeepsOptionsWithoutInventingHiddenResults() {
        // Structure observed anonymously on S1 thread-2293746-1-1.html (2026-11-13).
        Document document = Jsoup.parse("<form id='poll' name='poll'>"
                + "<input type='hidden' name='formhash' value='private-form-value'>"
                + "<div class='poll_txt'>单选投票, 共有 996 人参与投票</div>"
                + "<div class='poll_txt'>距结束还有: 4 天 22 小时 20 分钟</div>"
                + "<div class='poll_box'>"
                + "<p><label for='option_1'>1.拼多多</label></p><hr>"
                + "<p><label for='option_2'>2.淘宝</label></p><hr>"
                + "<p><label for='option_3'>3.天猫</label></p><hr>"
                + "<p><label for='option_4'>4.咸鱼</label></p><hr>"
                + "<p><label for='option_5'>5.京东</label></p><hr>"
                + "<p><label for='option_6'>6.抖音商城</label></p><hr>"
                + "<p><label for='option_7'>7.其他</label></p><hr>"
                + "<span class='xi1'>您需要<a href='member.php'>登录</a>之后方能进行投票</span>"
                + "</div></form>");

        List<Poll> polls = PollParsers.discuzPolls(document, "常用购物平台？");

        assertEquals(1, polls.size());
        Poll poll = polls.get(0);
        assertEquals("常用购物平台？", poll.question);
        assertEquals(7, poll.options.size());
        assertEquals("拼多多", poll.options.get(0).text);
        assertEquals("其他", poll.options.get(6).text);
        assertTrue(poll.options.stream().allMatch(option -> option.result.isEmpty()));
        assertTrue(poll.status.contains("共有 996 人参与投票"));
        assertTrue(poll.status.contains("登录之后方能进行投票"));
        assertFalse(poll.status.contains("拼多多"));
        assertFalse(poll.status.contains("private-form-value"));
        assertEquals(7, document.select("label").size());
    }

    @Test
    public void desktopResultsBelongToTheirPrecedingOption() {
        Document document = Jsoup.parse("<form id='poll'><div class='pinf'>单选投票</div>"
                + "<div class='pcht'><table summary='poll panel'>"
                + "<tr><td><input type='radio' name='pollanswers[]'></td>"
                + "<td class='pvt'><label for='option_1'>1. 方案甲</label></td></tr>"
                + "<tr><td><div class='pbg'><div class='pbr' style='width:75%'></div></div></td>"
                + "<td>75% <em>(3)</em></td></tr>"
                + "<tr><td class='pvt'><label for='option_2'>2. 方案乙</label></td></tr>"
                + "<tr><td><div class='pbg'><div class='pbr' style='width:25%'></div></div></td>"
                + "<td>25% <em>(1)</em></td></tr>"
                + "<tr><td><button>提交</button>您已经投过票</td></tr>"
                + "</table></div></form>");

        Poll poll = PollParsers.discuzPolls(document, "选哪个？").get(0);

        assertEquals("方案甲", poll.options.get(0).text);
        assertEquals("75% (3)", poll.options.get(0).result);
        assertEquals("25% (1)", poll.options.get(1).result);
        assertEquals("单选投票 您已经投过票", poll.status);
    }

    @Test
    public void touchResultsShareTheOptionParagraph() {
        Document document = Jsoup.parse("<form id='poll'><div>多选投票，最多选 2 项</div>"
                + "<p>投票已经结束</p><div><p><input type='checkbox' name='pollanswers[]'>"
                + "<label for='option_1'>1.甲 &amp; 乙</label>50% <em>(2)</em></p>"
                + "<p><label for='option_2'>2.丙</label>50% <em>(2)</em></p></div></form>");

        Poll poll = PollParsers.discuzPolls(document, "").get(0);

        assertEquals("甲 & 乙", poll.options.get(0).text);
        assertEquals("50% (2)", poll.options.get(0).result);
        assertTrue(poll.status.contains("最多选 2 项"));
        assertTrue(poll.status.contains("投票已经结束"));
    }

    @Test
    public void ignoresQuotedOrHiddenPollsAndNeverReadsHiddenResults() {
        String form = "<form id='poll'><p><label for='option_1'>1.选项</label>"
                + "<span hidden>99票</span><span style='display: none'>100%</span>"
                + "<input type='hidden' name='votes' value='99'></p></form>";
        Document document = Jsoup.parse("<blockquote>" + form + "</blockquote>"
                + "<div style='visibility:hidden'>" + form + "</div>" + form);

        List<Poll> polls = PollParsers.discuzPolls(document, "题目");

        assertEquals(1, polls.size());
        assertEquals("选项", polls.get(0).options.get(0).text);
        assertEquals("", polls.get(0).options.get(0).result);
        assertFalse(polls.get(0).status.contains("99"));
    }

    @Test
    public void imagePollCaptionsAndPublicResultsRemainPaired() {
        Document document = Jsoup.parse("<form id='poll'><div class='pinf'>图片投票</div>"
                + "<table><tr><td><div class='polltd'><img src='photo.jpg'>"
                + "<p class='mtn mbn xi2'><label><input name='pollanswers[]'></label>山景</p>"
                + "<div class='imgf'><p class='imgfc'><span>3票</span><span>75%</span></p></div>"
                + "</div></td><td><div class='polltd'><p class='mtn mbn xi2'>海景</p>"
                + "<div class='imgf'><p class='imgfc'><span>1票</span><span>25%</span></p></div>"
                + "</div></td></tr></table></form>");

        Poll poll = PollParsers.discuzPolls(document, "喜欢的风景").get(0);

        assertEquals(2, poll.options.size());
        assertEquals("山景", poll.options.get(0).text);
        assertEquals("3票 75%", poll.options.get(0).result);
        assertEquals("海景", poll.options.get(1).text);
        assertEquals("1票 25%", poll.options.get(1).result);
        assertEquals("图片投票", poll.status);
    }
}
