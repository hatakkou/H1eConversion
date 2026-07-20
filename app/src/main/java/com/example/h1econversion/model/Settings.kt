package com.example.h1econversion.model

/**
 * 変換設定を表すデータモデル。
 *
 * ユーザーが設定画面から変更可能なパラメータを保持します。
 * デフォルト値は現状のハードコード値と一致させています。
 */
data class ConversionSettings(
    /** 出力コーデック */
    val codec: CodecType = CodecType.AAC,
    /** 出力ビットレート（AAC 時のみ有効） */
    val bitrate: BitratePreset = BitratePreset.BITRATE_192,
    /** 出力コンテナ / 拡張子 */
    val container: ContainerType = ContainerType.M4A,
)

/**
 * 出力コーデック種別。
 */
enum class CodecType(
    /** 画面表示用ラベル */
    val displayName: String,
) {
    /** AAC-LC エンコード */
    AAC("AAC"),
    /** PCM/WAV パススルー（ゲインのみ適用し無圧縮出力） */
    PCM_WAV("PCM (WAV)"),
}

/**
 * AAC 出力ビットレートプリセット。
 *
 * @param bps ビットレート（bits per second）
 */
enum class BitratePreset(
    val bps: Int,
    /** 画面表示用ラベル */
    val displayName: String,
) {
    BITRATE_128(128_000, "128 kbps"),
    BITRATE_192(192_000, "192 kbps"),
    BITRATE_256(256_000, "256 kbps"),
    BITRATE_320(320_000, "320 kbps"),
}

/**
 * 出力コンテナ種別（拡張子に対応）。
 *
 * @param extension 拡張子（ドットなし、例: "m4a"）
 */
enum class ContainerType(
    val extension: String,
    /** 画面表示用ラベル */
    val displayName: String,
) {
    /** MPEG-4 コンテナ（AAC） */
    M4A("m4a", ".m4a (MPEG-4 Audio)"),
    /** Raw AAC ストリーム（ADTS ヘッダー付き） */
    AAC_RAW("aac", ".aac (Raw AAC)"),
    /** WAV（PCM パススルー時のみ有効） */
    WAV("wav", ".wav (PCM)"),
}
