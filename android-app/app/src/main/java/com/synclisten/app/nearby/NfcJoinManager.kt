package com.synclisten.app.nearby

import android.content.Context
import android.content.Intent
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.os.Build
import com.synclisten.app.invite.JoinLinkCodec
import com.synclisten.app.invite.JoinLinkInbox
import dagger.hilt.android.qualifiers.ApplicationContext
import java.nio.charset.StandardCharsets
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

object NfcJoinCodec {
    fun findJoinLink(candidates: List<String?>): String? =
        candidates.firstNotNullOfOrNull { it?.takeIf { value -> JoinLinkCodec.parse(value) != null } }
}

enum class NfcJoinStatus {
    READY,
    UNSUPPORTED,
    DISABLED,
    INVALID_TAG,
    LINK_RECEIVED,
}

data class NfcJoinState(
    val status: NfcJoinStatus,
    val message: String,
)

interface NfcJoinManager {
    val state: StateFlow<NfcJoinState>
    fun refreshAvailability()
    fun handleIntent(intent: Intent)
}

@Singleton
class AndroidNfcJoinManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val joinLinkInbox: JoinLinkInbox,
) : NfcJoinManager {
    private val adapter: NfcAdapter? = NfcAdapter.getDefaultAdapter(context)
    private val mutableState = MutableStateFlow(NfcJoinState(NfcJoinStatus.READY, "NFC 可用"))
    override val state: StateFlow<NfcJoinState> = mutableState

    init {
        refreshAvailability()
    }

    override fun refreshAvailability() {
        mutableState.value = when {
            adapter == null -> NfcJoinState(NfcJoinStatus.UNSUPPORTED, "设备不支持 NFC；其他加入方式仍可使用")
            !adapter.isEnabled -> NfcJoinState(NfcJoinStatus.DISABLED, "NFC 未开启；其他加入方式仍可使用")
            else -> NfcJoinState(NfcJoinStatus.READY, "NFC 可用，贴近包含邀请链接的 Tag")
        }
    }

    override fun handleIntent(intent: Intent) {
        val candidates = buildList {
            add(intent.dataString)
            intent.ndefMessages().flatMapTo(this) { message ->
                message.records.mapNotNull(::recordText)
            }
        }
        val link = NfcJoinCodec.findJoinLink(candidates)
        if (link == null) {
            if (intent.action == NfcAdapter.ACTION_NDEF_DISCOVERED) {
                mutableState.value = NfcJoinState(NfcJoinStatus.INVALID_TAG, "NFC Tag 不含有效加入链接")
            }
            return
        }
        joinLinkInbox.accept(link)
        mutableState.value = NfcJoinState(NfcJoinStatus.LINK_RECEIVED, "已读取 NFC 邀请，请确认加入")
    }

    @Suppress("DEPRECATION")
    private fun Intent.ndefMessages(): List<NdefMessage> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES, NdefMessage::class.java)?.toList().orEmpty()
        } else {
            getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES)?.filterIsInstance<NdefMessage>().orEmpty()
        }

    private fun recordText(record: NdefRecord): String? {
        record.toUri()?.toString()?.let { return it }
        if (record.tnf != NdefRecord.TNF_WELL_KNOWN || !record.type.contentEquals(NdefRecord.RTD_TEXT)) return null
        val payload = record.payload
        if (payload.isEmpty()) return null
        val languageLength = payload[0].toInt() and 0x3f
        val start = 1 + languageLength
        if (start >= payload.size) return null
        val charset = if ((payload[0].toInt() and 0x80) != 0) StandardCharsets.UTF_16 else StandardCharsets.UTF_8
        return String(payload, start, payload.size - start, charset)
    }
}
