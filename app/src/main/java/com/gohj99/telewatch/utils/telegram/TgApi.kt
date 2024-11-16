/*
 * Copyright (c) 2024 gohj99. Lorem ipsum dolor sit amet, consectetur adipiscing elit.
 * Morbi non lorem porttitor neque feugiat blandit. Ut vitae ipsum eget quam lacinia accumsan.
 * Etiam sed turpis ac ipsum condimentum fringilla. Maecenas magna.
 * Proin dapibus sapien vel ante. Aliquam erat volutpat. Pellentesque sagittis ligula eget metus.
 * Vestibulum commodo. Ut rhoncus gravida arcu.
 */

package com.gohj99.telewatch.utils.telegram

import android.content.Context
import android.os.Build
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import com.gohj99.telewatch.R
import com.gohj99.telewatch.ui.main.Chat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import java.io.File
import java.io.IOException
import java.util.Properties
import java.util.concurrent.CountDownLatch
import kotlin.coroutines.resume

class TgApi(
    private val context: Context,
    private var chatsList: MutableState<List<Chat>>,
    private val userId: String = "",
    private val topTitle: MutableState<String>,
    private val chatsFoldersList: MutableState<List<TdApi.ChatFolder>>
) {
    private var saveChatId = 1L
    private var saveChatList = mutableStateOf(emptyList<TdApi.Message>())
    private val client: Client = Client.create({ update -> handleUpdate(update) }, null, null)
    private val sharedPref = context.getSharedPreferences("LoginPref", Context.MODE_PRIVATE)
    @Volatile private var isAuthorized: Boolean = false
    private val authLatch = CountDownLatch(1)
    private var isExitChatPage = true
    private var lastReadOutboxMessageId = mutableStateOf(0L)
    private var lastReadInboxMessageId = mutableStateOf(0L)
    private var currentUser: List<String> = emptyList()

    init {
        // 获取应用外部数据目录
        val externalDir: File = context.getExternalFilesDir(null)
            ?: throw IllegalStateException("Failed to get external directory.")
        // 获取API ID和API Hash
        val config = loadConfig(context)
        val tdapiId = config.getProperty("api_id").toInt()
        val tdapiHash = config.getProperty("api_hash")
        val encryptionKeyString = sharedPref.getString("encryption_key", null)
        client.send(TdApi.SetTdlibParameters().apply {
            databaseDirectory = externalDir.absolutePath + (if (userId == "") "/tdlib" else {
                "/$userId/tdlib"
            })
            useMessageDatabase = true
            useSecretChats = true
            apiId = tdapiId
            apiHash = tdapiHash
            systemLanguageCode = context.resources.configuration.locales[0].language
            deviceModel = Build.MODEL
            systemVersion = Build.VERSION.RELEASE
            applicationVersion = getAppVersion(context)
            useSecretChats = false
            useMessageDatabase = true
            databaseEncryptionKey = encryptionKeyString?.chunked(2)?.map { it.toInt(16).toByte() }
                ?.toByteArray()
                ?: throw IllegalStateException("Encryption key not found")
        }) { result ->
            println("SetTdlibParameters result: $result")
            if (result is TdApi.Error) {
                throw IllegalStateException(result.message)
            }
        }

        // 等待授权状态更新
        try {
            authLatch.await()
        } catch (e: InterruptedException) {
            close()
            throw IllegalStateException("Interrupted while waiting for authorization", e)
        }

        if (!isAuthorized) {
            close()
            throw IllegalStateException("Failed to authorize")
        }
    }

    // 处理 TDLib 更新的函数
    private fun handleUpdate(update: TdApi.Object) {
        when (update.constructor) {
            TdApi.UpdateAuthorizationState.CONSTRUCTOR -> handleAuthorizationState(update as TdApi.UpdateAuthorizationState)
            TdApi.UpdateNewMessage.CONSTRUCTOR -> handleNewMessage(update as TdApi.UpdateNewMessage)
            TdApi.UpdateMessageContent.CONSTRUCTOR -> handleMessageContentUpdate(update as TdApi.UpdateMessageContent)
            TdApi.UpdateMessageEdited.CONSTRUCTOR -> handleMessageEdited(update as TdApi.UpdateMessageEdited)
            TdApi.UpdateDeleteMessages.CONSTRUCTOR -> handleDeleteMessages(update as TdApi.UpdateDeleteMessages)
            TdApi.UpdateNewChat.CONSTRUCTOR -> handleNewChat(update as TdApi.UpdateNewChat)
            TdApi.UpdateConnectionState.CONSTRUCTOR -> handleConnectionUpdate(update as TdApi.UpdateConnectionState)
            TdApi.UpdateChatReadInbox.CONSTRUCTOR -> handleChatReadInboxUpdate(update as TdApi.UpdateChatReadInbox)
            TdApi.UpdateChatReadOutbox.CONSTRUCTOR -> handleChatReadOutboxUpdate(update as TdApi.UpdateChatReadOutbox)
            TdApi.UpdateChatFolders.CONSTRUCTOR -> handleChatFoldersUpdate(update as TdApi.UpdateChatFolders)
            // 其他更新
            else -> {
                //println("Received update: $update")
            }
        }
    }

    private fun handleChatReadInboxUpdate(update: TdApi.UpdateChatReadInbox) {
        val chatId = update.chatId
        if (chatId == saveChatId) {
            lastReadInboxMessageId.value = update.lastReadInboxMessageId
        }
    }

    private fun handleChatReadOutboxUpdate(update: TdApi.UpdateChatReadOutbox) {
        val chatId = update.chatId
        if (chatId == saveChatId) {
            lastReadOutboxMessageId.value = update.lastReadOutboxMessageId
        }
    }

    // 获取聊天文件夹
    private fun handleChatFoldersUpdate(update: TdApi.UpdateChatFolders) {
        chatsFoldersList.value = emptyList()
        update.chatFolders?.let { chatFolders ->
            CoroutineScope(Dispatchers.IO).launch {
                // 将每个异步任务放入列表
                val foldersInfo = chatFolders.map { chatFolder ->
                    async { getChatFolderInfo(chatFolder.id) }
                }.awaitAll() // 等待所有异步任务完成

                // 过滤非空结果，并按顺序更新 chatsFoldersList
                chatsFoldersList.value = foldersInfo.filterNotNull()
            }
        }
    }

    // 网络状态更新
    private fun handleConnectionUpdate(update: TdApi.UpdateConnectionState) {
        when (update.state.constructor) {
            TdApi.ConnectionStateReady.CONSTRUCTOR -> {
                // 已经成功连接到 Telegram 服务器
                topTitle.value = ""
                println("TgApi: Connection Ready")
            }

            TdApi.ConnectionStateConnecting.CONSTRUCTOR -> {
                // 正在尝试连接到 Telegram 服务器
                topTitle.value = context.getString(R.string.Connecting)
                println("TgApi: Connecting")
            }

            TdApi.ConnectionStateConnectingToProxy.CONSTRUCTOR -> {
                // 正在尝试通过代理连接到 Telegram 服务器
                topTitle.value = context.getString(R.string.Connecting)
                println("TgApi: Connecting To Proxy")
            }

            TdApi.ConnectionStateUpdating.CONSTRUCTOR -> {
                // 正在更新 Telegram 数据库
                topTitle.value = context.getString(R.string.Update)
                println("TgApi: Updating")
            }

            TdApi.ConnectionStateWaitingForNetwork.CONSTRUCTOR -> {
                // 正在等待网络连接
                topTitle.value = context.getString(R.string.Offline)
                println("TgApi: Waiting For Network")
            }

            else -> {
                // 其他网络状态处理
            }
        }
    }

    // 处理授权状态更新
    private fun handleAuthorizationState(update: TdApi.UpdateAuthorizationState) {
        val authorizationState = update.authorizationState
        when (authorizationState.constructor) {
            TdApi.AuthorizationStateReady.CONSTRUCTOR -> {
                println("TgApi: Authorization Ready")
                isAuthorized = true
                authLatch.countDown()
            }

            TdApi.AuthorizationStateClosed.CONSTRUCTOR -> {
                println("TgApi: Authorization Closed")
                isAuthorized = false
                authLatch.countDown()
            }

            TdApi.AuthorizationStateWaitPhoneNumber.CONSTRUCTOR -> {
                println("TgApi: Waiting for Phone Number")
                isAuthorized = false
                authLatch.countDown()
            }

            else -> {
                // 其他授权状态处理
            }
        }
    }

    // 处理删除消息
    private fun handleDeleteMessages(update: TdApi.UpdateDeleteMessages) {
        val chatId = update.chatId
        val messageIds = update.messageIds
        println("Messages deleted in chat ID $chatId: $messageIds")

        CoroutineScope(Dispatchers.IO).launch {
            val messageType = getMessageTypeById(messageIds[0], chatId)
            //println(messageType)
            if (messageType == null) {
                if (chatId == saveChatId) {
                    val mutableChatListSize = saveChatList.value.size
                    val mutableChatList = saveChatList.value.toMutableList()
                    for (messageId in messageIds) {
                        val message = mutableChatList.find { it.id == messageId }
                        if (message != null) {
                            // 更新保存的聊天列表
                            mutableChatList.remove(message)
                        }
                    }
                    if (mutableChatListSize - mutableChatList.size <= 1) saveChatList.value =
                        mutableChatList
                    reloadMessageById(messageIds[0])
                }

                // 更新聊天列表
                try {
                    val chatResult = sendRequest(TdApi.GetChat(chatId))
                    if (chatResult.constructor == TdApi.Chat.CONSTRUCTOR) {
                        withContext(Dispatchers.Main) {
                            chatsList.value = chatsList.value.toMutableList().apply {
                                // 查找现有的聊天并更新
                                val existingChatIndex = indexOfFirst { it.id == chatId }
                                if (existingChatIndex >= 0) {
                                    val updatedChat = get(existingChatIndex).copy(
                                        message = handleAllMessages((chatResult as TdApi.Chat).lastMessage)
                                    )
                                    removeAt(existingChatIndex)
                                    add(0, updatedChat)
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    println("GetChat request failed (handleDeleteMessages): ${e.message}")
                }
            }
        }
    }

    // 处理获取到的新消息
    private fun handleNewMessage(update: TdApi.UpdateNewMessage) {
        val message = update.message
        println("New message received in chat ID ${message.chatId}\nmessageId ${message.id}")
        //println(message)
        updateChatList(message)
    }

    // 处理消息内容更新
    private fun updateChatList(message: TdApi.Message) {
        val chatId = message.chatId
        val newMessageText = handleAllMessages(message)

        // 异步获取聊天标题
        CoroutineScope(Dispatchers.IO).launch {
            var chatTitle = context.getString(R.string.Unknown_chat)
            var isPinned = false
            var isRead = false
            var isBot = false
            var isChannel = false
            var isGroup = false
            var isPrivateChat = false
            try {
                val chatResult = sendRequest(TdApi.GetChat(chatId))
                chatTitle = if (chatResult.constructor == TdApi.Chat.CONSTRUCTOR) (chatResult as TdApi.Chat).title else chatTitle
                isPinned = chatResult.positions.firstOrNull()?.isPinned ?: false
                isRead = chatResult.isMarkedAsUnread
                when (val messageType = chatResult.type) {
                    is TdApi.ChatTypeSupergroup -> {
                        if (messageType.isChannel) {
                            isChannel = true
                        } else {
                            isGroup = true
                        }
                    }
                    is TdApi.ChatTypeBasicGroup -> {
                        isGroup = true
                    }
                    is TdApi.ChatTypePrivate -> {
                        isPrivateChat = true
                        val userResult = sendRequest(TdApi.GetUser(chatResult.id))
                        if (userResult.type is TdApi.UserTypeBot) {
                            isBot = true
                        }
                    }
                }
            } catch (e: Exception) {
                println("GetChat request failed (updateChatList): ${e.message}")
            }

            withContext(Dispatchers.Main) {
                if (chatId == saveChatId) {
                    // 将新消息添加到保存的聊天列表的前面
                    saveChatList.value = saveChatList.value.toMutableList().apply {
                        add(0, message) // 新消息存储在最前面
                    }
                }

                chatsList.value = chatsList.value.toMutableList().apply {
                    // 查找现有的聊天并更新
                    val existingChatIndex = indexOfFirst { it.id == chatId }
                    if (existingChatIndex >= 0) {
                        val updatedChat = get(existingChatIndex).copy(
                            message = newMessageText,
                            isPinned = isPinned,
                            isRead = isRead,
                            isBot = isBot,
                            isChannel = isChannel,
                            isGroup = isGroup,
                            isPrivateChat = isPrivateChat
                        )
                        removeAt(existingChatIndex)
                        add(0, updatedChat)
                    } else {
                        // 新增聊天到列表顶部
                        add(
                            0,
                            Chat(
                                id = chatId,
                                title = chatTitle, // 使用从TdApi获取的标题
                                message = newMessageText,
                                isPinned = isPinned,
                                isRead = isRead,
                                isBot = isBot,
                                isChannel = isChannel,
                                isGroup = isGroup,
                                isPrivateChat = isPrivateChat
                            )
                        )
                    }
                }
            }
        }
    }

    // 处理消息内容更新
    private fun handleMessageContentUpdate(update: TdApi.UpdateMessageContent) {
        val chatId = update.chatId
        val messageId = update.messageId
        //val newContent = update.newContent
        println("Message content updated in chat ID $chatId for message ID $messageId")

        /*CoroutineScope(Dispatchers.Main).launch {
            chatsList.value = chatsList.value.toMutableList().apply {
                val chatIndex = indexOfFirst { it.id == chatId }
                if (chatIndex >= 0) {
                    val updatedChat = get(chatIndex).copy(
                        message = if (newContent is TdApi.MessageText) {
                            val newMessageText =
                                if (newContent.text.text.length > 20) newContent.text.text.take(20) + "..." else newContent.text.text
                            newMessageText
                        } else context.getString(R.string.Unknown_Message)
                    )
                    removeAt(chatIndex)
                    add(0, updatedChat)
                }
            }
        }*/
    }

    // 处理消息编辑
    private fun handleMessageEdited(update: TdApi.UpdateMessageEdited) {
        val chatId = update.chatId
        val messageId = update.messageId
        val editDate = update.editDate
        println("Message edited in chat ID $chatId for message ID $messageId at $editDate")

        if (chatId == saveChatId) {
            CoroutineScope(Dispatchers.IO).launch {
                // 异步获取消息的最新内容
                val getMessageRequest = TdApi.GetMessage(chatId, messageId)
                try {
                    val result = sendRequest(getMessageRequest)
                    if (result.constructor == TdApi.Message.CONSTRUCTOR) {
                        val message = result as TdApi.Message

                        // 更新聊天列表中的消息
                        withContext(Dispatchers.Main) {
                            saveChatList.value = saveChatList.value.toMutableList().apply {
                                val messageIndex = indexOfFirst { it.id == messageId }
                                if (messageIndex >= 0) {
                                    // 找到消息并替换内容
                                    val updatedMessage = TdApi.Message().apply {
                                        this.chatId = message.chatId
                                        this.id = message.id
                                        this.date = message.date
                                        this.senderId = message.senderId
                                        this.content = message.content
                                        this.isOutgoing = message.isOutgoing
                                    }
                                    set(messageIndex, updatedMessage)
                                }
                            }
                        }
                    } else {
                        println("Failed to get message content: $result")
                    }
                } catch (e: Exception) {
                    println("Get message content request failed: ${e.message}")
                }
            }
        }

        // 更新聊天列表
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val chatResult = sendRequest(TdApi.GetChat(chatId))
                if (chatResult.constructor == TdApi.Chat.CONSTRUCTOR) {
                    withContext(Dispatchers.Main) {
                        chatsList.value = chatsList.value.toMutableList().apply {
                            // 查找现有的聊天并更新
                            val existingChatIndex = indexOfFirst { it.id == chatId }
                            if (existingChatIndex >= 0) {
                                val updatedChat = get(existingChatIndex).copy(
                                    message = handleAllMessages((chatResult as TdApi.Chat).lastMessage)
                                )
                                removeAt(existingChatIndex)
                                add(0, updatedChat)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                println("GetChat request failed (handleMessageEdited): ${e.message}")
            }
        }
    }

    // 处理新聊天
    private fun handleNewChat(update: TdApi.UpdateNewChat){
        //println(update)
        val newChat = update.chat
        val chatId = newChat.id

        //println(newChat.lastMessage)
        //println(update)
        //println(newChat.positions.firstOrNull()?.isPinned ?: false)

        var isPinned = newChat.positions.firstOrNull()?.isPinned ?: false
        var isRead = false
        var isBot = false
        var isChannel = false
        var isGroup = false
        var isPrivateChat = false
        var chatTitle = newChat.title
        var lastMessage = handleAllMessages(newChat.lastMessage)
        var havePositions = true
        // 异步获取聊天标题
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val chatResult = sendRequest(TdApi.GetChat(chatId))
                if (chatResult.constructor == TdApi.Chat.CONSTRUCTOR) {
                    if (chatResult.positions.isEmpty()) havePositions = false
                    isPinned = chatResult.positions.firstOrNull()?.isPinned ?: false
                    chatTitle = chatResult.title
                    lastMessage = handleAllMessages(chatResult.lastMessage)
                    isRead = chatResult.isMarkedAsUnread
                    when (val messageType = chatResult.type) {
                        is TdApi.ChatTypeSupergroup -> {
                            if (messageType.isChannel) {
                                isChannel = true
                            } else {
                                isGroup = true
                            }
                        }
                        is TdApi.ChatTypeBasicGroup -> {
                            isGroup = true
                        }
                        is TdApi.ChatTypePrivate -> {
                            isPrivateChat = true
                            val userResult = sendRequest(TdApi.GetUser(chatResult.id))
                            if (userResult.type is TdApi.UserTypeBot) {
                                isBot = true
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                println("GetChat request failed (handleNewChat): ${e.message}")
            }
            withContext(Dispatchers.Main) {
                chatsList.value = chatsList.value.toMutableList().apply {
                    // 查找现有的聊天并更新
                    val existingChatIndex = indexOfFirst { it.id == chatId }
                    if (existingChatIndex >= 0) {
                        // 如果存在该聊天，更新并移动到顶部
                        val updatedChat = get(existingChatIndex).copy(
                            title = chatTitle,
                            message = lastMessage
                        )
                        removeAt(existingChatIndex)  // 移除旧的聊天
                        add(0, updatedChat)  // 将更新后的聊天添加到顶部
                    } else {
                        // 如果不存在该聊天，添加到列表末尾
                        if (havePositions) {
                            add(
                                Chat(
                                    id = chatId,
                                    title = chatTitle, // 使用从 TdApi 获取的标题
                                    message = lastMessage,
                                    isPinned = isPinned,
                                    isRead = isRead,
                                    isBot = isBot,
                                    isChannel = isChannel,
                                    isGroup = isGroup,
                                    isPrivateChat = isPrivateChat
                                )
                            )
                        }

                    }
                }
            }
        }
    }

    // 强制加载消息
    private fun addNewChat(chatId: Long){
        // 异步获取聊天标题
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val chatResult = sendRequest(TdApi.GetChat(chatId))
                if (chatResult.constructor == TdApi.Chat.CONSTRUCTOR) {

                    var isBot = false
                    var isChannel = false
                    var isGroup = false
                    var isPrivateChat = false
                    var havePositions = true

                    if (chatResult.positions.isEmpty()) havePositions = false
                    val isPinned = chatResult.positions.firstOrNull()?.isPinned ?: false
                    val chatTitle = chatResult.title
                    val lastMessage = handleAllMessages(chatResult.lastMessage)
                    val isRead = chatResult.isMarkedAsUnread
                    when (val messageType = chatResult.type) {
                        is TdApi.ChatTypeSupergroup -> {
                            if (messageType.isChannel) {
                                isChannel = true
                            } else {
                                isGroup = true
                            }
                        }
                        is TdApi.ChatTypeBasicGroup -> {
                            isGroup = true
                        }
                        is TdApi.ChatTypePrivate -> {
                            isPrivateChat = true
                            val userResult = sendRequest(TdApi.GetUser(chatResult.id))
                            if (userResult.type is TdApi.UserTypeBot) {
                                isBot = true
                            }
                        }
                    }

                    // 加入聊天列表
                    withContext(Dispatchers.Main) {
                        chatsList.value = chatsList.value.toMutableList().apply {
                            // 查找现有的聊天并更新
                            val existingChatIndex = indexOfFirst { it.id == chatId }
                            if (existingChatIndex >= 0) {
                                // 如果存在该聊天，更新并移动到顶部
                                val updatedChat = get(existingChatIndex).copy(
                                    title = chatTitle,
                                    message = lastMessage
                                )
                                removeAt(existingChatIndex)  // 移除旧的聊天
                                add(0, updatedChat)  // 将更新后的聊天添加到顶部
                            } else {
                                // 如果不存在该聊天，添加到列表末尾
                                if (havePositions) {
                                    add(
                                        Chat(
                                            id = chatId,
                                            title = chatTitle, // 使用从 TdApi 获取的标题
                                            message = lastMessage,
                                            isPinned = isPinned,
                                            isRead = isRead,
                                            isBot = isBot,
                                            isChannel = isChannel,
                                            isGroup = isGroup,
                                            isPrivateChat = isPrivateChat
                                        )
                                    )
                                }

                            }
                        }
                    }
                }
            } catch (e: Exception) {
                println("GetChat request failed (handleNewChat): ${e.message}")
            }
        }
    }

    // 处理和简化消息
    private fun handleAllMessages(message: TdApi.Message?): String {
        if (message == null) return context.getString(R.string.Unknown_Message)
        return when (val content = message.content) {
            is TdApi.MessageText -> if (content.text.text.length > 20) content.text.text.take(20) + "..." else content.text.text
            is TdApi.MessagePhoto -> context.getString(R.string.Photo)
            is TdApi.MessageVideo -> context.getString(R.string.Video)
            is TdApi.MessageVoiceNote -> context.getString(R.string.Voice)
            is TdApi.MessageAnimation -> context.getString(R.string.Animation)
            is TdApi.MessageAnimatedEmoji -> if (content.emoji == "") context.getString(R.string.Unknown_Message) else content.emoji
            is TdApi.MessageSticker -> if (content.sticker.emoji == "") context.getString(R.string.Unknown_Message) else content.sticker.emoji
            else -> context.getString(R.string.Unknown_Message)
        }
    }

    // 加载配置
    private fun loadConfig(context: Context): Properties {
        val properties = Properties()
        try {
            val inputStream = context.assets.open("config.properties")
            inputStream.use { properties.load(it) }
        } catch (e: IOException) {
            e.printStackTrace()
            // 处理异常，例如返回默认配置或通知用户
        }
        return properties
    }

    // 获取应用版本
    private fun getAppVersion(context: Context): String {
        return try {
            val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            pInfo.versionName
        } catch (e: Exception) {
            "1.0.0"
        }
    }

    // 下载文件
    fun downloadFile(
        file: TdApi.File,
        schedule: (String) -> Unit,
        completion: (Boolean, String?) -> Unit
    ) {
        // 判断文件是否已经下载完成
        if (file.local.isDownloadingCompleted) {
            // 文件已经下载完成，直接返回
            completion(true, file.local.path)
        } else {
            // 开始下载文件
            client.send(TdApi.DownloadFile(file.id, 1, 0, 0, true)) { response ->
                when (response) {
                    is TdApi.Error -> {
                        // 下载失败，回调completion
                        println("文件下载失败: ${response.message}")
                        completion(false, null)
                    }

                    is TdApi.File -> {
                        // 检查下载进度
                        val downloadProgress =
                            if (response.local.downloadedSize > 0 && response.expectedSize > 0) {
                                (response.local.downloadedSize * 100 / response.expectedSize).toString() + "%"
                            } else {
                                "未知进度"
                            }

                        // 回调schedule以更新进度
                        schedule(downloadProgress)

                        // 检查是否下载完成
                        if (response.local.isDownloadingCompleted) {
                            // 下载完成，回调completion并传递文件路径
                            println("文件下载完成: ${response.local.path}")
                            completion(true, response.local.path)
                        } else {
                            // 下载未完成，继续回调schedule直到下载完成
                            println("下载进行中: $downloadProgress")
                        }
                    }

                    else -> {
                        println("下载出现未知错误")
                        completion(false, null)
                    }
                }
            }
        }
    }

    // 下载照片
    fun downloadPhoto(file: TdApi.File, completion: (Boolean, String?) -> Unit) {
        //println("进入下载图片函数")
        if (file.local.isDownloadingCompleted) {
            //println("下载过直接返回")
            /*runBlocking {
                reloadMessageById(messageId)
            }*/
            // 文件已经下载完成，直接返回
            completion(true, file.local.path)
        } else {
            //println("哦，貌似没下载过，那就开始下载吧")
            // 文件未下载，开始下载
            client.send(TdApi.DownloadFile(file.id, 1, 0, 0, true)) { response ->
                when (response) {
                    is TdApi.Error -> {
                        // 下载失败
                        println("下载失败")
                        completion(false, null)
                    }

                    is TdApi.File -> {
                        if (response.local.isDownloadingCompleted) {
                            // 下载完成
                            //println("下载完成，在" + response.local.path)
                            completion(true, response.local.path)
                        } else {
                            // 下载未完成或失败
                            println("下载未完成")
                            completion(false, null)
                        }
                    }

                    else -> {
                        println("下载失败")
                        // 其他情况，下载失败
                        completion(false, null)
                    }
                }
            }
        }
    }

    fun searchPublicChat(username: String, callback: (TdApi.Chat?) -> Unit) {
        // 异步搜索公共聊天
        client.send(TdApi.SearchPublicChat(username)) { response ->
            if (response is TdApi.Chat) {
                callback(response)
            } else {
                callback(null)
            }
        }
    }

    // 按用户名搜索公共聊天
    suspend fun searchPublicChats(
        query: String,
        searchList: MutableState<List<Chat>>
    ) {
        println("查询中")
        val searchResult = sendRequest(TdApi.SearchPublicChats(query))
        searchResult.let {
            if (it is TdApi.Chats) {
                // 搜索成功
                println("搜索成功")
                //println(searchResult)
                searchList.value = emptyList()
                searchList.value = withContext(Dispatchers.IO) {
                    searchResult.chatIds.map { id ->
                        var isPinned = false
                        var isRead = false
                        var isBot = false
                        var isChannel = false
                        var isGroup = false
                        var isPrivateChat = false
                        var chatTitle = "error"
                        var lastMessage = ""
                        try {
                            val chatResult = sendRequest(TdApi.GetChat(id))
                            if (chatResult.constructor == TdApi.Chat.CONSTRUCTOR) {
                                println(chatResult)
                                isPinned = chatResult.positions.firstOrNull()?.isPinned ?: false
                                chatTitle = chatResult.title
                                lastMessage = handleAllMessages(chatResult.lastMessage)
                                isRead = chatResult.isMarkedAsUnread
                                when (val messageType = chatResult.type) {
                                    is TdApi.ChatTypeSupergroup -> {
                                        if (messageType.isChannel) {
                                            isChannel = true
                                        } else {
                                            isGroup = true
                                        }
                                    }

                                    is TdApi.ChatTypeBasicGroup -> {
                                        isGroup = true
                                    }

                                    is TdApi.ChatTypePrivate -> {
                                        isPrivateChat = true
                                        val userResult =
                                            sendRequest(TdApi.GetUser(chatResult.id))
                                        if (userResult.type is TdApi.UserTypeBot) {
                                            isBot = true
                                        }
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            println("GetChat request failed (handleNewChat): ${e.message}")
                        }
                        Chat(
                            id = id,
                            title = chatTitle,
                            message = lastMessage,
                            isPinned = isPinned,
                            isRead = isRead,
                            isBot = isBot,
                            isChannel = isChannel,
                            isGroup = isGroup,
                            isPrivateChat = isPrivateChat
                        )
                    }
                }
            }
        }
    }

    // 发送加入聊天请求
    fun joinChat(chatId: Long, reInit: () -> Unit) {
        client.send(TdApi.JoinChat(chatId)) { result ->
            if (result is TdApi.Ok) {
                // 加入聊天成功
                //println("Joined the chat successfully")
                addNewChat(chatId)
                reInit()
            } else {
                // 加入聊天失败
                println("Failed to join chat")
            }
        }
    }

    // 获取lastReadOutboxMessageId
    fun getLastReadOutboxMessageId(): MutableState<Long> {
        return lastReadOutboxMessageId
    }

    // 获取lastReadOutboxMessageId
    fun getLastReadInboxMessageId(): MutableState<Long> {
        return lastReadInboxMessageId
    }

    // 获取用户名
    fun getUser(userId: Long, onResult: (String) -> Unit) {
        val getUserRequest = TdApi.GetUser(userId)

        client.send(getUserRequest) { result ->
            if (result is TdApi.User) {
                val fullName = "${result.firstName} ${result.lastName}".trim()
                onResult(fullName)
            } else {
                onResult("Unknown User")
            }
        }
    }

    // 获取聊天对象
    suspend fun getChat(chatId: Long): TdApi.Chat? {
        return suspendCancellableCoroutine { continuation ->
            val getChatRequest = TdApi.GetChat(chatId)

            // 发送异步请求
            client.send(getChatRequest) { result ->
                if (result is TdApi.Chat) {
                    // 当结果是 TdApi.Chat 时，恢复协程并返回 Chat 对象
                    continuation.resume(result)
                } else {
                    // 在其他情况下，恢复协程并返回 null
                    continuation.resume(null)
                }
            }
        }
    }

    // 退出登录
    fun logOut() {
        client.send(TdApi.LogOut()) { result ->
            when (result.constructor) {
                TdApi.Ok.CONSTRUCTOR -> {
                    println("Logged out successfully")
                }

                else -> {
                    println("Failed to log out: $result")
                }
            }
        }
    }

    // 获取联系人
    fun getContacts(contacts: MutableState<List<Chat>>) {
        val request = TdApi.GetContacts()
        client.send(request) { result ->
            when (result.constructor) {
                TdApi.Error.CONSTRUCTOR -> {
                    val error = result as TdApi.Error
                    println("Error getting contacts: ${error.message}")
                }
                TdApi.Users.CONSTRUCTOR -> {
                    val users = result as TdApi.Users
                    val userIds = users.userIds

                    // 异步获取每个用户的详细信息
                    CoroutineScope(Dispatchers.IO).launch {
                        for (userId in userIds) {
                            try {
                                val userResult = sendRequest(TdApi.GetUser(userId))
                                if (userResult.constructor == TdApi.User.CONSTRUCTOR) {
                                    val user = userResult as TdApi.User
                                    withContext(Dispatchers.Main) {
                                        // 检查是否已存在相同 ID 的联系人
                                        val existingContacts = contacts.value.toMutableList()
                                        val existingContactIndex =
                                            existingContacts.indexOfFirst { it.id == user.id }
                                        if (existingContactIndex != -1) {
                                            // 替换原有的联系人
                                            existingContacts[existingContactIndex] = Chat(
                                                id = user.id,
                                                title = "${user.firstName} ${user.lastName}",
                                                message = ""
                                            )
                                        } else {
                                            // 添加新联系人
                                            existingContacts.add(
                                                Chat(
                                                    id = user.id,
                                                    title = "${user.firstName} ${user.lastName}",
                                                    message = ""
                                                )
                                            )
                                        }
                                        // 更新状态
                                        contacts.value = existingContacts
                                    }
                                } else {
                                    println("Unexpected result type for user ID $userId: ${userResult.javaClass.name}")
                                }
                            } catch (e: Exception) {
                                println("GetUser request failed: ${e.message}")
                            }
                        }
                    }
                }
                else -> {
                    println("Unexpected result type: ${result.constructor}")
                }
            }
        }
    }

    // 标记已读
    fun markMessagesAsRead(messageId: Long, forceRead: Boolean = true) {
        // 创建 ViewMessages 请求
        val viewMessagesRequest = TdApi.ViewMessages(
            saveChatId,
            longArrayOf(messageId),
            null,
            forceRead
        )

        // 发送 ViewMessages 请求
        client.send(viewMessagesRequest) { response ->
            if (response is TdApi.Ok) {
                //println("Messages successfully marked as read in chat ID $saveChatId")
            } else {
                println("Failed to mark messages as read: $response")
            }
        }
    }

    // 获取分组信息
    private suspend fun getChatFolderInfo(chatFolderId: Int): TdApi.ChatFolder? {
        try {
            val result = sendRequest(TdApi.GetChatFolder(chatFolderId))
            return result
        } catch (e: Exception) {
            println("Error getting chat folders: ${e.message}")
        }
        return null
    }

    suspend fun createPrivateChat(userId: Long) {
        try {
            sendRequest(TdApi.CreatePrivateChat(userId, false))
        } catch (e: Exception) {
            println("Error create private chat: ${e.message}")
        }
    }

    // 发送消息
    fun sendMessage(chatId: Long, messageText: String): TdApi.Message? {
        var sentMessage: TdApi.Message? = null
        val message = TdApi.SendMessage().apply {
            this.chatId = chatId
            inputMessageContent = TdApi.InputMessageText().apply {
                text = TdApi.FormattedText().apply {
                    this.text = messageText
                }
            }
        }
        client.send(message) { result ->
            println("SendMessage result: $result")
            if (result.constructor == TdApi.Error.CONSTRUCTOR) {
                val error = result as TdApi.Error
                println("Send Message Error: ${error.message}")
            } else {
                sentMessage = result as TdApi.Message
                println("Message sent successfully")
            }
        }
        return sentMessage
    }

    // 加载聊天列表
    suspend fun loadChats(limit: Int = 15){
        val loadChats = TdApi.LoadChats(TdApi.ChatListFolder(0), limit)
        try {
            val result = sendRequest(loadChats)
            println("LoadChats result: $result")
        } catch (e: Exception) {
            println("LoadChats request failed: ${e.message}")
        }
    }

    // 根据消息id删除消息
    fun deleteMessageById(messageId: Long) {
        println("Deleting message")
        runBlocking {
            // 创建一个请求来删除指定 ID 的消息
            val getMessageRequest = TdApi.DeleteMessages(saveChatId, longArrayOf(messageId) , true)
            try {
                val result = sendRequest(getMessageRequest)
                if (result.constructor == TdApi.Message.CONSTRUCTOR) {
                    println("Message deleted successfully")
                } else {
                    println("Failed to reload message with ID $messageId: $result")
                }
            } catch (e: Exception) {
                println("DeleteMessage request failed: ${e.message}")
            }
        }
    }

    // 根据消息id更新消息
    fun reloadMessageById(messageId: Long) {
        println("Reloading message")
        CoroutineScope(Dispatchers.IO).launch {
            // 创建一个请求来获取指定 ID 的消息
            val getMessageRequest = TdApi.GetMessage(saveChatId, messageId)
            try {
                val result = sendRequest(getMessageRequest)
                if (result.constructor == TdApi.Message.CONSTRUCTOR) {
                    val message = result as TdApi.Message

                    //println(message)

                    // 使用重新加载的消息更新 saveChatList
                    withContext(Dispatchers.Main) {
                        saveChatList.value = saveChatList.value.toMutableList().apply {
                            val index = indexOfFirst { it.id == messageId }
                            if (index >= 0) {
                                // 更新已存在的消息
                                set(index, message)
                            } else {
                                // 如果列表中没有此消息，则将其添加到列表的开头
                                add(0, message)
                            }
                        }
                    }
                } else {
                    println("Failed to reload message with ID $messageId: $result")
                }
            } catch (e: Exception) {
                println("GetMessage request failed: ${e.message}")
            }
        }
    }

    // 获取当前用户 ID 的方法
    suspend fun getCurrentUser(): List<String> {
        if (currentUser.isEmpty()) {
            try {
                val result = sendRequest(TdApi.GetMe())
                if (result.constructor == TdApi.User.CONSTRUCTOR) {
                    val user = result as TdApi.User
                    currentUser = listOf(user.id.toString(), "${user.firstName} ${user.lastName}")
                    return currentUser
                } else {
                    throw IllegalStateException("Failed to get current user ID")
                }
            } catch (e: Exception) {
                println("GetMe request failed: ${e.message}")
                throw IllegalStateException("Failed to get current user ID")
            }
        } else {
            return currentUser
        }
    }

    // 发送请求并返回结果
    private suspend fun <R : TdApi.Object> sendRequest(
        request: TdApi.Function<R>,
        retryCount: Int = 3 // 重试次数限制
    ): R = withContext(Dispatchers.IO) {
        val result = CompletableDeferred<R>()
        client.send(request) { response ->
            when (response) {
                is TdApi.Error -> {
                    if (response.code == 404) {
                        // 错误码是 404，直接抛出异常
                        result.completeExceptionally(
                            Exception("TDLib error 404: ${response.message}")
                        )
                    } else if (retryCount > 0) {
                        // 错误码不是 404，并且还可以重试，递归调用 sendRequest
                        launch {
                            try {
                                val retryResult = sendRequest(request, retryCount - 1)
                                result.complete(retryResult)
                            } catch (e: Exception) {
                                result.completeExceptionally(e)
                            }
                        }
                    } else {
                        // 超过重试次数，抛出异常
                        result.completeExceptionally(
                            Exception("TDLib error: ${response.message}")
                        )
                    }
                }
                else -> {
                    // 成功时，完成请求
                    @Suppress("UNCHECKED_CAST")
                    result.complete(response as R)
                }
            }
        }
        return@withContext result.await()
    }

    // 退出聊天页面
    fun exitChatPage(){
        isExitChatPage = true
    }

    // 获取聊天记录
    fun getChatMessages(
        chatId: Long,
        chatList: MutableState<List<TdApi.Message>>
    ) {
        saveChatList = chatList
        saveChatId = chatId
        isExitChatPage = false

        // 定义一个内部函数用于异步递归获取消息
        fun fetchMessages(fromMessageId: Long) {
            val getChatMessages = TdApi.GetChatHistory().apply {
                this.chatId = chatId
                this.limit = 10 // 每次获取 10 条消息
                this.fromMessageId = fromMessageId
            }

            if (!isExitChatPage){
                client.send(getChatMessages) { result ->
                    //println("GetChatMessages result: $result")
                    if (result.constructor == TdApi.Error.CONSTRUCTOR) {
                        val error = result as TdApi.Error
                        println("Get Chat Messages Error: ${error.message}")
                    } else {
                        val messages = result as TdApi.Messages
                        if (messages.messages.isNotEmpty()) {
                            val sortedMessages =
                                messages.messages.toList().sortedByDescending { it.date }
                            saveChatList.value = saveChatList.value.toMutableList().apply {
                                addAll(sortedMessages) // 将新消息添加到列表最后面
                            }
                            // 继续加载更旧的消息
                            fetchMessages(messages.messages.last().id)
                        }
                    }
                }
            }
        }

        // 从最新的消息开始获取
        fetchMessages(0)
    }

    // 根据消息id获取消息
    suspend fun getMessageTypeById(messageId: Long, chatId: Long = saveChatId): TdApi.Message? {

        val getMessageRequest = TdApi.GetMessage(chatId, messageId)

        try {
            val result = sendRequest(getMessageRequest)
            if (result.constructor == TdApi.Message.CONSTRUCTOR) {
                val message = result as TdApi.Message
                return message
            } else {
                println("Failed to get message with ID $messageId: $result")
                return null
            }
        } catch (e: Exception) {
            return null
        }
    }

    // 关闭连接
    fun close() {
        println("Closing client")
        runBlocking {
            sendRequest(TdApi.Close())
        }
    }
}