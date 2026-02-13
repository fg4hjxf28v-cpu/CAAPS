package info.nightscout.androidaps.plugins.pump.carelevo.ui.dialog

import android.text.SpannableString
import android.text.Spanned
import android.text.style.LeadingMarginSpan
import android.widget.TextView
import info.nightscout.androidaps.plugins.pump.carelevo.R
import info.nightscout.androidaps.plugins.pump.carelevo.databinding.DialogInsulinRefillGuideBinding
import info.nightscout.androidaps.plugins.pump.carelevo.ui.base.BaseFullScreenDialog

class InsulinRefillGuideDialog : BaseFullScreenDialog<DialogInsulinRefillGuideBinding>() {

    override val layoutResId: Int = R.layout.dialog_insulin_refill_guide
    override fun init() {
        setupViews()
    }

    private fun setupViews() = with(binding) {
        btnDismiss.setOnClickListener {
            dismiss()
        }

        applyHangingIndent(tvStep1)
        applyHangingIndent(tvStep2)
        applyHangingIndent(tvStep3)
        applyHangingIndent(tvStep4)
        applyHangingIndent(tvStep5)
        applyHangingIndent(tvStep6)
        applyHangingIndent(tvStep7)
        applyHangingIndent(tvStep8)
        applyHangingIndent(tvStep9)
        applyHangingIndent(tvStep10)
        applyHangingIndent(tvStep11)
        applyHangingIndent(tvStep12)
    }

    private fun applyHangingIndent(textView: TextView) {
        val fullText = textView.text.toString()
        if (fullText.isBlank()) return

        // "1. " 같은 prefix 추출 (숫자 + '.' + 공백 기준)
        val dotIndex = fullText.indexOf('.')
        if (dotIndex <= 0 || dotIndex + 1 >= fullText.length) return

        val prefix = fullText.substring(0, dotIndex + 2) // 예: "1. ", "2. "

        val paint = textView.paint
        val indentPx = paint.measureText(prefix).toInt()

        val spannable = SpannableString(fullText).apply {
            // 첫 줄은 그대로, 두 번째 줄부터 prefix 폭만큼 들여쓰기
            setSpan(
                LeadingMarginSpan.Standard(0, indentPx),
                0,
                fullText.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        textView.text = spannable
    }

    companion object {

        fun newInstance(): InsulinRefillGuideDialog =
            InsulinRefillGuideDialog()
    }
}