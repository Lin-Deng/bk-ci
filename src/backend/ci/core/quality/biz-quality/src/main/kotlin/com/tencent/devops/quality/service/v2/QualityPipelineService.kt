/*
 * Tencent is pleased to support the open source community by making BK-CI 蓝鲸持续集成平台 available.
 *
 * Copyright (C) 2019 THL A29 Limited, a Tencent company.  All rights reserved.
 *
 * BK-CI 蓝鲸持续集成平台 is licensed under the MIT license.
 *
 * A copy of the MIT License is included in this file.
 *
 *
 * Terms of the MIT License:
 * ---------------------------------------------------
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation
 * files (the "Software"), to deal in the Software without restriction, including without limitation the rights to use, copy,
 * modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the
 * Software is furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT
 * LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN
 * NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
 * WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE
 * SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package com.tencent.devops.quality.service.v2

import com.tencent.devops.common.api.util.HashUtil
import com.tencent.devops.common.client.Client
import com.tencent.devops.common.pipeline.pojo.element.Element
import com.tencent.devops.plugin.codecc.CodeccUtils
import com.tencent.devops.process.api.service.ServicePipelineResource
import com.tencent.devops.process.api.service.ServicePipelineTaskResource
import com.tencent.devops.process.api.template.ServiceTemplateResource
import com.tencent.devops.quality.api.v2.pojo.RulePipelineRange
import com.tencent.devops.quality.api.v2.pojo.RuleTemplateRange
import com.tencent.devops.quality.api.v2.pojo.response.RangeExistElement
import com.tencent.devops.quality.util.ElementUtils
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service

@Service
class QualityPipelineService @Autowired constructor(
    private val client: Client,
    private val indicatorService: QualityIndicatorService
) {
    fun userListPipelineRangeDetail(projectId: String, pipelineIds: Set<String>, indicatorIds: Collection<String>, controlPointType: String?): List<RulePipelineRange> {
        val pipelineElementsMap = client.get(ServicePipelineTaskResource::class).list(projectId, pipelineIds).data
            ?: mapOf()
        val pipelineNameMap = client.get(ServicePipelineResource::class).getPipelineNameByIds(projectId, pipelineIds).data
            ?: mapOf()
        val indicatorElements = indicatorService.serviceList(indicatorIds.map { HashUtil.decodeIdToLong(it) }).map { it.elementType }

        // 加入控制点的判断
        val checkElements = if (!controlPointType.isNullOrBlank()) {
            indicatorElements.plus(controlPointType!!)
        } else {
            indicatorElements
        }

        // 剔除已删除的流水线
        return pipelineElementsMap.entries.filter { pipelineNameMap.containsKey(it.key) }.map {
            val pipelineElement = it.value.map { it.atomCode to it.taskParams }
            // 获取原子信息
            val elementResult = getExistAndLackElements(projectId, checkElements, pipelineElement)
            val existElements = elementResult.first
            val lackElements = elementResult.second

            RulePipelineRange(
                pipelineId = it.key,
                pipelineName = pipelineNameMap[it.key] ?: "",
                elementCount = pipelineElement.size,
                lackPointElement = lackElements.map { ElementUtils.getElementCnName(it, projectId) },
                existElement = existElements
            )
        }
    }

    fun userListTemplateRangeDetail(projectId: String, templateIds: Set<String>, indicatorIds: Collection<String>, controlPointType: String?): List<RuleTemplateRange> {
        val templateMap = if (templateIds.isNotEmpty()) client.get(ServiceTemplateResource::class)
            .listTemplateById(templateIds, null).data?.templates ?: mapOf()
        else mapOf()
        val templateElementsMap = templateMap.map {
            val model = it.value
            val elements = mutableListOf<Element>()
            model.stages.map { it.containers.map { elements.addAll(it.elements) } }
            it.value.templateId to elements
        }.toMap()
        val templateNameMap = templateMap.map { it.value.templateId to it.value.name }.toMap()

        val indicatorElements = indicatorService.serviceList(indicatorIds.map { HashUtil.decodeIdToLong(it) }).map { it.elementType }
        val checkElements = if (!controlPointType.isNullOrBlank()) {
            indicatorElements.plus(controlPointType!!)
        } else {
            indicatorElements
        }

        // 剔除已删除的流水线
        return templateElementsMap.entries.filter { templateNameMap.containsKey(it.key) }.filter { it.key in templateIds }.map { entry ->
            val templateId = entry.key
            val templateElements = entry.value.map { it.getAtomCode() to it.genTaskParams() }
            // 获取原子信息
            val elementResult = getExistAndLackElements(projectId, checkElements, templateElements)
            val existElements = elementResult.first
            val lackElements = elementResult.second
            RuleTemplateRange(
                templateId = templateId,
                templateName = templateNameMap[templateId] ?: "",
                elementCount = templateElements.size,
                lackPointElement = lackElements.map { ElementUtils.getElementCnName(it, projectId) },
                existElement = existElements
            )
        }
    }

    private fun getExistAndLackElements(
        projectId: String,
        checkElements: List<String>,
        originElementList: List<Pair<String, Map<String, Any>>>
    ): Pair<List<RangeExistElement>, Set<String>> {
        // 1. 查找不存在的插件
        val lackElements = mutableSetOf<String>()
        checkElements.forEach { checkElement ->
            val isExist = originElementList.any { originElement -> checkElement == originElement.first ||
                CodeccUtils.isCodeccAtom(checkElement) == CodeccUtils.isCodeccAtom(originElement.first) }
            if (!isExist) lackElements.add(checkElement)
        }

        // 2. 查找存在的插件
        // 找出流水线存在的指标原子，并统计个数
        val indicatorExistElement = checkElements.minus(lackElements) // 流水线对应的指标原子
        val existElements = mutableListOf<RangeExistElement>()
        originElementList.filter { it.first in indicatorExistElement }.groupBy { it.first }
            .forEach { (classType, tasks) ->
                val ele = if (CodeccUtils.isCodeccAtom(classType)) {
                    val asynchronous = tasks.any { t ->
                        val asynchronous = t.second["asynchronous"] as? Boolean
                        asynchronous == true
                    }
                    RangeExistElement(
                        name = classType,
                        cnName = ElementUtils.getElementCnName(classType, projectId),
                        count = 1,
                        params = mapOf("asynchronous" to (asynchronous))
                    )
                } else {
                    RangeExistElement(
                        name = classType,
                        cnName = ElementUtils.getElementCnName(classType, projectId),
                        count = tasks.size
                    )
                }
                existElements.add(ele)
            }
        return Pair(existElements, lackElements)
    }
}