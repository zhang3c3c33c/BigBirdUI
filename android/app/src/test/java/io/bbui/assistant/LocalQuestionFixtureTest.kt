package io.bbui.assistant

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class LocalQuestionFixtureTest {
    private fun tool() = JSONObject().put("role", "tool").put("tool_call_id", "question-fixture").put("content", "选择: 甲, \"测试补充 😀\" (other)")
    @Test fun onlyExactLatestToolAnswerPasses() {
        assertTrue(LocalQuestionFixture.receivedAnswer(JSONArray().put(tool())))
        assertFalse(LocalQuestionFixture.receivedAnswer(JSONArray().put(tool().put("isError", true))))
        assertFalse(LocalQuestionFixture.receivedAnswer(JSONArray().put(tool().put("tool_call_id", "other"))))
        assertFalse(LocalQuestionFixture.receivedAnswer(JSONArray().put(tool()).put(tool().put("content", "工具调用失败"))))
        assertFalse(LocalQuestionFixture.receivedAnswer(JSONArray().put(tool()).put(JSONObject().put("role", "user").put("content", "new task"))))
        assertFalse(LocalQuestionFixture.receivedAnswer(JSONArray().put(tool().put("role", "assistant"))))
    }
    @Test fun pureTextAnswerAndSkillPromptAreCheckedIndependently() {
        val answer = tool().put("content", "收件人: \"测试补充 😀\" (other)")
        assertTrue(LocalQuestionFixture.receivedAnswer(JSONArray().put(answer), true))
        assertFalse(LocalQuestionFixture.receivedAnswer(JSONArray().put(answer)))
        val location = "/data/user/0/test/pi/skills/phone-operation/SKILL.md"
        val system = JSONObject().put("role", "system").put("content", "<location>$location</location>")
        assertEquals(location, LocalQuestionFixture.skillPath(JSONArray().put(system)))
        assertFalse(LocalQuestionFixture.receivedSkill(JSONArray().put(system)))
        assertTrue(LocalQuestionFixture.receivedSkill(JSONArray().put(JSONObject().put("role", "tool")
            .put("tool_call_id", "skill-fixture").put("content", "name: phone-operation"))))
    }
    @Test fun oldSkillReadCannotSatisfyTheCurrentTurn() {
        val success = JSONObject().put("role", "tool").put("tool_call_id", "skill-fixture").put("content", "name: phone-operation")
        val failed = JSONObject(success.toString()).put("content", "指南读取失败")
        val user = JSONObject().put("role", "user").put("content", "another task")
        assertFalse(LocalQuestionFixture.receivedSkill(JSONArray().put(success).put(failed)))
        assertFalse(LocalQuestionFixture.receivedSkill(JSONArray().put(success).put(user)))
        assertFalse(LocalQuestionFixture.receivedSkill(JSONArray().put(success).put(user).put(failed)))
        assertTrue(LocalQuestionFixture.receivedSkill(JSONArray().put(success).put(user).put(JSONObject(success.toString()))))
    }
}
