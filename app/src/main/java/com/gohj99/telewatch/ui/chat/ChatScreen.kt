/*
 * Copyright (c) 2024 gohj99. Lorem ipsum dolor sit amet, consectetur adipiscing elit.
 * Morbi non lorem porttitor neque feugiat blandit. Ut vitae ipsum eget quam lacinia accumsan.
 * Etiam sed turpis ac ipsum condimentum fringilla. Maecenas magna.
 * Proin dapibus sapien vel ante. Aliquam erat volutpat. Pellentesque sagittis ligula eget metus.
 * Vestibulum commodo. Ut rhoncus gravida arcu.
 */

package com.gohj99.telewatch.ui.chat

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.rememberAsyncImagePainter
import com.gohj99.telewatch.R
import com.gohj99.telewatch.TgApiManager
import com.gohj99.telewatch.ui.theme.TelewatchTheme
import org.drinkless.td.libcore.telegram.TdApi
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun formatTimestampToTime(unixTimestamp: Long): String {
    // 将 Unix 时间戳转换为毫秒
    val date = Date(unixTimestamp * 1000)

    // 定义时间格式
    val format = SimpleDateFormat("HH:mm", Locale.getDefault())

    // 返回格式化的时间字符串
    return format.format(date)
}

@Composable
fun SplashChatScreen(
    chatTitle: String,
    chatList: MutableState<List<TdApi.Message>>,
    currentUserId: Long,
    sendCallback: (String) -> Unit
) {
    val listState = rememberLazyListState()
    var isFloatingVisible by remember { mutableStateOf(true) }
    var inputText by remember { mutableStateOf(TextFieldValue("")) }
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current

    LaunchedEffect(listState) {
        var previousIndex = listState.firstVisibleItemIndex
        var previousScrollOffset = listState.firstVisibleItemScrollOffset

        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
            .collect { (index, scrollOffset) ->
                if (index != previousIndex) {
                    if (index > previousIndex + 2) {
                        isFloatingVisible = false
                    }
                } else {
                    if (scrollOffset > previousScrollOffset) {
                        isFloatingVisible = false
                    }
                }
                previousIndex = index
                previousScrollOffset = scrollOffset
            }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 0.dp) // 调整垂直填充
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            //println("开始渲染")
            Text(
                text = chatTitle,
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .weight(1f),
                reverseLayout = true, // 反转布局
                verticalArrangement = Arrangement.Top
            ) {
                item {
                    Spacer(modifier = Modifier.height(70.dp)) // 添加一个高度为 70dp 的 Spacer
                }
                items(chatList.value) { message ->
                    val isCurrentUser =
                        (message.senderId as? TdApi.MessageSenderUser)?.userId == currentUserId
                    val backgroundColor =
                        if (isCurrentUser) Color(0xFF003C68) else Color(0xFF2C323A)
                    val textColor = if (isCurrentUser) Color(0xFF66D3FE) else Color(0xFFFEFEFE)
                    val alignment = if (isCurrentUser) Arrangement.End else Arrangement.Start

                    Row(
                        modifier = Modifier
                            .padding(6.dp)
                            .fillMaxWidth(),
                        horizontalArrangement = alignment
                    ) {
                        Box(
                            modifier = Modifier
                                .background(backgroundColor, shape = RoundedCornerShape(8.dp))
                                .padding(8.dp)
                        ) {
                            when (val content = message.content) {
                                is TdApi.MessageText -> {
                                    Text(
                                        text = content.text.text,
                                        color = textColor,
                                        fontSize = 18.sp
                                    )
                                }
                                is TdApi.MessagePhoto -> {
                                    val thumbnail = content.photo.sizes.minByOrNull { it.width * it.height }
                                    if (thumbnail != null) {
                                        ThumbnailImage(
                                            message = message,
                                            thumbnail = thumbnail.photo,
                                            imageWidth = thumbnail.width,
                                            imageHeight = thumbnail.height,
                                            textColor = textColor
                                        )
                                    } else {
                                        // 处理没有缩略图的情况
                                        Text(
                                            text = stringResource(id = R.string.No_thumbnail_available),
                                            color = textColor,
                                            fontSize = 18.sp
                                        )
                                    }
                                }
                                else -> {
                                    Text(
                                        text = stringResource(id = R.string.Unknown_Message),
                                        color = textColor,
                                        fontSize = 18.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // 隐藏的 TextField 用于触发输入法
        val textFieldFocusRequester by remember { mutableStateOf(FocusRequester()) }

        TextField(
            value = inputText,
            onValueChange = { inputText = it },
            modifier = Modifier
                .size(1.dp)
                .alpha(0f)
                .focusRequester(textFieldFocusRequester),
            maxLines = 1,
            singleLine = true,
            keyboardOptions = KeyboardOptions.Default.copy(
                imeAction = ImeAction.Done
            ),
            keyboardActions = KeyboardActions(
                onDone = {
                    focusManager.clearFocus()
                    keyboardController?.hide()
                    // TODO: Handle the input text (e.g., send message)
                }
            )
        )

        if (isFloatingVisible) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 4.dp)
                    .alpha(1f),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = {
                        textFieldFocusRequester.requestFocus() // 将焦点移动到隐藏的 TextField
                        keyboardController?.show() // 显示输入法
                    },
                    modifier = Modifier
                        .size(84.dp)
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.ic_custom_keyboard),
                        contentDescription = null,
                        modifier = Modifier.size(82.dp)
                    )
                }

                IconButton(
                    onClick = { sendCallback(inputText.text) },
                    modifier = Modifier
                        .size(45.dp)
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.ic_custom_send),
                        contentDescription = null,
                        modifier = Modifier.size(45.dp)
                    )
                }
            }
        } else {
            Box(
                modifier = Modifier
                    .fillMaxHeight() // 使 Box 填满整个屏幕高度
                    .fillMaxWidth(), // 使 Box 填满整个屏幕宽度
                contentAlignment = Alignment.BottomCenter // 将内容对齐到 Box 的底部中心
            ) {
                IconButton(
                    onClick = {
                        isFloatingVisible = true
                    },
                    modifier = Modifier
                        .padding(3.dp) // 可选的内边距
                        .size(20.dp) // 设置 IconButton 的大小
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.up), // 替换为你自己的向上箭头图标资源ID
                        contentDescription = null,
                        modifier = Modifier
                            .size(20.dp) // 设置 Image 的大小
                            .graphicsLayer(alpha = 0.5f) // 设置 Image 的不透明度
                    )
                }
            }
        }
    }
}

@Composable
fun ThumbnailImage(
    message: TdApi.Message,
    thumbnail: TdApi.File,
    imageWidth: Int,
    imageHeight: Int,
    textColor: Color
) {
    //println(imageWidth)
    //println(imageHeight)
    val isDownloaded = remember { mutableStateOf(false) }
    val heightDp = with(LocalDensity.current) { imageHeight.toDp() }
    val widthDp = with(LocalDensity.current) { imageWidth.toDp() }
    val chatId = message.id

    if (!isDownloaded.value) {
        LaunchedEffect(thumbnail.id) {
            TgApiManager.tgApi!!.downloadThumbnailPhoto(thumbnail, chatId) { success ->
                if (success) {
                    isDownloaded.value = true
                } else {
                    // 处理下载失败
                }
            }
        }
    }

    if (isDownloaded.value) {
        if (message.content is TdApi.MessagePhoto) {
            val content = message.content as TdApi.MessagePhoto
            val thumbnailNew = content.photo.sizes.minByOrNull { it.width * it.height }
            if (thumbnailNew != null) {
                println(thumbnailNew.photo.local.path)
                Image(
                    painter = rememberAsyncImagePainter(model = thumbnailNew.photo.local.path),
                    contentDescription = "Thumbnail",
                    modifier = Modifier.size(width = widthDp, height = heightDp)
                )
            }
        }
    } else {
        // 显示加载中状态或占位符
        Box(
            modifier = Modifier.size(width = widthDp, height = heightDp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = stringResource(id = R.string.loading),
                color = textColor,
                fontSize = 18.sp
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
fun SplashChatScreenPreview() {
    val sampleMessages = remember {
        mutableStateOf(
            listOf(
                TdApi.Message().apply {
                    date = 1692127800
                    id = 1
                    senderId = TdApi.MessageSenderUser(1) // 当前用户
                    content = TdApi.MessageText(
                        TdApi.FormattedText(
                            "我去，大佬你用qt开发的吗，太美了",
                            emptyArray()
                        ), null
                    )
                },
                TdApi.Message().apply {
                    id = 2
                    senderId = TdApi.MessageSenderUser(2) // 对方用户
                    content = TdApi.MessageText(TdApi.FormattedText("你再骂！", emptyArray()), null)
                },
                TdApi.Message().apply {
                    id = 3
                    senderId = TdApi.MessageSenderUser(2) // 对方用户
                    content = TdApi.MessageText(
                        TdApi.FormattedText(
                            "这可是用高贵的jetpack compose写的。\n原生啊，原生懂吗？",
                            emptyArray()
                        ), null
                    )
                }
            )
        )
    }

    TelewatchTheme {
        SplashChatScreen(
            chatTitle = "XCちゃん",
            chatList = sampleMessages,
            currentUserId = 1L
        ) { text ->
            println(text)
        }
    }
}
