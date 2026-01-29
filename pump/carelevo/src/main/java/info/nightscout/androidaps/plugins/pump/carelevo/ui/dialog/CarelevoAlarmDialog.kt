package info.nightscout.androidaps.plugins.pump.carelevo.ui.dialog

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.TextView
import androidx.annotation.ColorInt
import androidx.annotation.DrawableRes
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.text.HtmlCompat
import androidx.core.view.isVisible
import dagger.android.support.DaggerDialogFragment
import info.nightscout.androidaps.plugins.pump.carelevo.databinding.DialogCarelevoAlarmBinding
import info.nightscout.androidaps.plugins.pump.carelevo.domain.model.alarm.CarelevoAlarmInfo

class CarelevoAlarmDialog : DaggerDialogFragment() {

    private var title = ""
    private var content = ""
    private var alarmInfo: CarelevoAlarmInfo? = null
    private var primaryButton: Button? = null
    private var muteButton: Button? = null
    private var mute5minButton: Button? = null

    private var _binding: DialogCarelevoAlarmBinding? = null
    private val binding get() = _binding!!

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {

        dialog?.window?.requestFeature(Window.FEATURE_NO_TITLE)
        dialog?.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN)
        isCancelable = false
        dialog?.setCanceledOnTouchOutside(false)

        _binding = DialogCarelevoAlarmBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        init()
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }

    fun init() {
        setupViews()
    }

    fun setupViews() {
        with(binding) {
            tvTitle.text = title
            tvContent.text = if ((content.contains("<b>") && content.contains("</b>"))
                || content.contains("<font")
                || content.contains("<br>")
            ) {
                HtmlCompat.fromHtml(content, HtmlCompat.FROM_HTML_MODE_LEGACY)
            } else {
                content
            }
            tvContent.isVisible = content.isNotBlank()

            initButton(tvPrimaryButton, primaryButton, isDismiss = true)
            initButton(tvMute, muteButton)
            initButton(tvMute5min, mute5minButton)
        }
    }

    private fun initButton(view: TextView, button: Button?, isDismiss: Boolean = false) {
        if (button == null) {
            view.visibility = View.GONE
            return
        }

        view.apply {
            text = button.text
            button.textColor?.let { setTextColor(it) }
            button.background?.let {
                AppCompatResources.getDrawable(context, it)?.let { bg -> background = bg }
            }
            setOnClickListener {
                button.onClickListener?.invoke()
                if (isDismiss) dismiss()
            }
            visibility = View.VISIBLE
        }
    }

    data class Button(
        val text: String,
        @ColorInt val textColor: Int? = null,
        @DrawableRes val background: Int? = null,
        val onClickListener: (() -> Unit)? = null
    )

    class Builder {

        private var title = ""
        private var content = ""
        private var alarmInfo: CarelevoAlarmInfo? = null
        private var primaryButton: Button? = null
        private var muteButton: Button? = null
        private var mute5minButton: Button? = null

        fun setTitle(title: String) = apply { this.title = title }
        fun setContent(content: String) = apply { this.content = content }
        fun setAlarmInfo(alarmInfo: CarelevoAlarmInfo) = apply { this.alarmInfo = alarmInfo }
        fun setPrimaryButton(button: Button) = apply { this.primaryButton = button }
        fun setMuteButton(button: Button) = apply { this.muteButton = button }
        fun setMute5minButton(button: Button) = apply { this.mute5minButton = button }

        fun build(): CarelevoAlarmDialog {
            return CarelevoAlarmDialog().apply {
                this.title = this@Builder.title
                this.content = this@Builder.content
                this.alarmInfo = this@Builder.alarmInfo
                this.primaryButton = this@Builder.primaryButton
                this.muteButton = this@Builder.muteButton
                this.mute5minButton = this@Builder.mute5minButton
            }
        }
    }
}