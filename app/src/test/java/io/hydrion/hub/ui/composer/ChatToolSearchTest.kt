package io.hydrion.hub.ui.composer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class ChatToolSearchTest {
    @Test fun groupNameRevealsItsExistingOptionsInOriginalOrder() {
        val web = listOf("Built-in model search", "Custom search", "Disabled")
        assertSame(web, matchingToolChoices("   ", "Web search", web) { it })
        assertSame(web, matchingToolChoices(" WeB ", "Web search", web) { it })
        assertEquals(listOf("Custom search"), matchingToolChoices("cust", "Web search", web) { it })
    }

    @Test fun localizedOptionNamesCanMatchAcrossGroupsOrReturnNoResults() {
        val web = listOf("模型内置搜索", "自定义搜索", "禁用")
        val image = listOf("自定义图片生成", "禁用")
        assertEquals(listOf("禁用"), matchingToolChoices("禁用", "网络搜索", web) { it })
        assertEquals(listOf("禁用"), matchingToolChoices("禁用", "图片生成", image) { it })
        assertEquals(emptyList<String>(), matchingToolChoices("文件", "网络搜索", web) { it })
    }
}
