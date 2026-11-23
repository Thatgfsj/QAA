package com.thatgfsj.qaa.feature.normal.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 模型目录 fake 状态 JVM 单测（Issue #8 验收）。 */
class ModelManageViewModelTest {

    @Test
    fun 默认目录_含冒烟模型_未加载() {
        val vm = ModelManageViewModel()
        val catalog = vm.models.value
        assertEquals(1, catalog.size)
        val supra = catalog.single()
        assertEquals("Supra2.5-Pro-Instruct", supra.name)
        assertEquals("Q4_0", supra.quantization)
        assertEquals(40L * 1024 * 1024, supra.sizeBytes)
        assertFalse(supra.loaded)
    }

    @Test
    fun 切换加载状态_往返翻转() {
        val vm = ModelManageViewModel()
        val id = vm.models.value.single().id
        vm.toggleLoaded(id)
        assertTrue(vm.models.value.single().loaded)
        vm.toggleLoaded(id)
        assertFalse(vm.models.value.single().loaded)
    }

    @Test
    fun 未知id_不影响目录() {
        val vm = ModelManageViewModel()
        vm.toggleLoaded("no-such-model")
        assertFalse(vm.models.value.single().loaded)
    }

    @Test
    fun 体积格式化() {
        assertEquals("40.0 MB", formatSize(40L * 1024 * 1024))
    }
}
