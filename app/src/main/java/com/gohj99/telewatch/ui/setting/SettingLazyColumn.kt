/*
 * Copyright (c) 2024 gohj99. Lorem ipsum dolor sit amet, consectetur adipiscing elit.
 * Morbi non lorem porttitor neque feugiat blandit. Ut vitae ipsum eget quam lacinia accumsan.
 * Etiam sed turpis ac ipsum condimentum fringilla. Maecenas magna.
 * Proin dapibus sapien vel ante. Aliquam erat volutpat. Pellentesque sagittis ligula eget metus.
 * Vestibulum commodo. Ut rhoncus gravida arcu.
 */

package com.gohj99.telewatch.ui.setting

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.gohj99.telewatch.ui.main.MainCard

@Composable
fun SettingLazyColumn(itemsList: MutableState<List<String>>, callback: (String) -> Unit) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize() // 确保 LazyColumn 填满父容器
            .padding(horizontal = 16.dp) // 只在左右添加 padding
    ) {
        items(itemsList.value) { item ->
            SettingView(item, callback)
        }
        item {
            Spacer(modifier = Modifier.height(50.dp)) // 添加一个高度为 50dp 的 Spacer
        }
    }
}

@Composable
fun SettingView(item: String, callback: (String) -> Unit) {
    MainCard(
        column = {
            Text(text = item, color = Color.White)
        },
        item = item,
        callback = {
            callback(item)
        }
    )
}