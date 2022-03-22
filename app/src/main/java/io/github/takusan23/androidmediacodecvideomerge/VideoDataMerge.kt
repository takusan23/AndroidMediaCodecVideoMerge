package io.github.takusan23.androidmediacodecvideomerge

import android.media.*
import android.view.Surface
import java.io.File
import kotlin.concurrent.thread

/**
 * 映像データを結合する
 *
 * @param videoList 結合する動画、音声ファイルの配列。入っている順番どおりに結合します。
 * @param mergeFilePath 結合したファイルの保存先
 * @param bitRate ビットレート。何故か取れなかった
 * @param frameRate フレームレート。何故か取れなかった
 * */
class VideoDataMerge(
    videoList: List<File>,
    private val mergeFilePath: File,
    private val bitRate: Int = 1_000_000, // 1Mbps
    private val frameRate: Int = 30, // 30fps
) {

    /** タイムアウト */
    private val TIMEOUT_US = 10000L

    /** MediaCodecでもらえるInputBufferのサイズ */
    private val INPUT_BUFFER_SIZE = 655360

    /** 結合する動画の配列のイテレータ */
    private val videoListIterator = videoList.listIterator()

    /** ファイル合成 */
    private val mediaMuxer by lazy { MediaMuxer(mergeFilePath.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4) }

    /** 取り出した[MediaFormat] */
    private var currentMediaFormat: MediaFormat? = null

    /** 現在進行中の[MediaExtractor] */
    private var currentMediaExtractor: MediaExtractor? = null

    /** エンコード用 [MediaCodec] */
    private var encodeMediaCodec: MediaCodec? = null

    /** デコード用 [MediaCodec] */
    private var decodeMediaCodec: MediaCodec? = null

    /** エンコーダーへ流す[Surface] */
    private var inputSurface: Surface? = null

    /**
     * 結合を開始する
     *
     * 同期処理になるので、別スレッドで実行してください
     * */
    fun merge() {
        /**
         * MediaExtractorで動画ファイルを読み出す
         *
         * @param path 動画パス
         * */
        fun extractVideoFile(path: String) {
            // 動画の情報を読み出す
            val (_mediaExtractor, index, format) = extractMedia(path, "video/") ?: return
            currentMediaExtractor = _mediaExtractor
            currentMediaFormat = format
            // 音声のトラックを選択
            currentMediaExtractor?.selectTrack(index)
        }

        // 最初の動画を解析
        extractVideoFile(videoListIterator.next().path)

        // 解析結果から各パラメータを取り出す
        val mimeType = currentMediaFormat?.getString(MediaFormat.KEY_MIME)!! // video/avc
        val width = currentMediaFormat?.getInteger(MediaFormat.KEY_WIDTH)!! // 1280
        val height = currentMediaFormat?.getInteger(MediaFormat.KEY_HEIGHT)!! // 720

        // エンコーダーにセットするMediaFormat
        val videoMediaFormat = MediaFormat.createVideoFormat(mimeType, width, height).apply {
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, INPUT_BUFFER_SIZE)
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
        }

        // 映像を追加してトラック番号をもらう
        // 多分 addTrack する際は MediaExtractor 経由で取得した MediaFormat を入れないといけない？
        val videoTrackIndex = mediaMuxer.addTrack(currentMediaFormat!!)

        // エンコード用（生データ -> H.264）MediaCodec
        encodeMediaCodec = MediaCodec.createEncoderByType(mimeType).apply {
            configure(videoMediaFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        }
        // エンコーダーのSurfaceを取得
        // デコーダーの出力Surfaceの項目にこれを指定して、エンコーダーに映像データがSurface経由で行くようにする
        inputSurface = encodeMediaCodec!!.createInputSurface()
        // デコード用（H.264 -> 生データ）MediaCodec
        decodeMediaCodec = MediaCodec.createDecoderByType(mimeType).apply {
            // デコード時は MediaExtractor の MediaFormat で良さそう
            configure(currentMediaFormat!!, inputSurface, null, 0)
        }

        // nonNull
        val decodeMediaCodec = decodeMediaCodec!!
        val encodeMediaCodec = encodeMediaCodec!!
        // スタート
        decodeMediaCodec.start()
        encodeMediaCodec.start()
        mediaMuxer.start()

        // デコードが終わったフラグ
        var isEOLDecode = false

        /**
         * --- Surfaceに流れてきたデータをエンコードする ---
         * */
        thread {
            val outputBufferInfo = MediaCodec.BufferInfo()
            // デコード結果をもらう
            // デコーダーが生きている間のみ
            while (!isEOLDecode) {
                val outputBufferId = encodeMediaCodec.dequeueOutputBuffer(outputBufferInfo, TIMEOUT_US)
                if (outputBufferId >= 0) {
                    val outputBuffer = encodeMediaCodec.getOutputBuffer(outputBufferId)!!
                    // 書き込む
                    mediaMuxer.writeSampleData(videoTrackIndex, outputBuffer, outputBufferInfo)
                    // 返却
                    encodeMediaCodec.releaseOutputBuffer(outputBufferId, false)
                }
            }
        }

        /**
         *  --- 複数ファイルを全てデコードする ---
         * */
        var totalPresentationTime = 0L
        var prevPresentationTime = 0L
        // メタデータ格納用
        val decoderBufferInfo = MediaCodec.BufferInfo()
        while (true) {
            // デコーダー部分
            val inputBufferId = decodeMediaCodec.dequeueInputBuffer(TIMEOUT_US)
            if (inputBufferId >= 0) {
                // Extractorからデータを読みだす
                val inputBuffer = decodeMediaCodec.getInputBuffer(inputBufferId)!!
                val size = currentMediaExtractor!!.readSampleData(inputBuffer, 0)
                if (size > 0) {
                    // デコーダーへ流す
                    // 今までの動画の分の再生位置を足しておく
                    decodeMediaCodec.queueInputBuffer(inputBufferId, 0, size, currentMediaExtractor!!.sampleTime + totalPresentationTime, 0)
                    currentMediaExtractor!!.advance()
                    // 一個前の動画の動画サイズを控えておく
                    // else で extractor.sampleTime すると既に-1にっているので
                    if (currentMediaExtractor!!.sampleTime != -1L) {
                        prevPresentationTime = currentMediaExtractor!!.sampleTime
                    }
                } else {
                    totalPresentationTime += prevPresentationTime
                    // データがないので次データへ
                    if (videoListIterator.hasNext()) {
                        // 次データへ
                        val file = videoListIterator.next()
                        // 多分いる
                        decodeMediaCodec.queueInputBuffer(inputBufferId, 0, 0, 0, 0)
                        // 動画の情報を読み出す
                        currentMediaExtractor!!.release()
                        extractVideoFile(file.path)
                    } else {
                        // データなくなった場合は終了
                        decodeMediaCodec.queueInputBuffer(inputBufferId, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        // 開放
                        currentMediaExtractor!!.release()
                        // 終了
                        break
                    }
                }
            }
            // デコードした内容をエンコーダーへ移す
            val outputBufferId = decodeMediaCodec.dequeueOutputBuffer(decoderBufferInfo, TIMEOUT_US)
            if (outputBufferId >= 0) {
                // デコード結果をもらう。第二引数はtrueにしてSurfaceへ描画する
                decodeMediaCodec.releaseOutputBuffer(outputBufferId, true)
            }
        }

        // デコーダー終了
        isEOLDecode = true
        decodeMediaCodec.stop()
        decodeMediaCodec.release()

        // エンコーダー終了
        inputSurface?.release()
        encodeMediaCodec.stop()
        encodeMediaCodec.release()

        // MediaMuxerも終了
        mediaMuxer.stop()
        mediaMuxer.release()
    }

    /** 強制終了時に呼ぶ */
    fun stop() {
        decodeMediaCodec?.stop()
        decodeMediaCodec?.release()
        inputSurface?.release()
        encodeMediaCodec?.stop()
        encodeMediaCodec?.release()
        currentMediaExtractor?.release()
        mediaMuxer.stop()
        mediaMuxer.release()
    }

    /**
     * 引数に渡した動画パス[videoPath]の情報を[MediaExtractor]で取り出す
     *
     * @param mimeType 音声なら audio/ 動画なら video/
     * @param videoPath 動画の動画パス
     * */
    private fun extractMedia(videoPath: String, mimeType: String): Triple<MediaExtractor, Int, MediaFormat>? {
        println(videoPath)
        val mediaExtractor = MediaExtractor().apply { setDataSource(videoPath) }
        // 映像トラックとインデックス番号のPairを作って返す
        val (index, track) = (0 until mediaExtractor.trackCount)
            .map { index -> index to mediaExtractor.getTrackFormat(index) }
            .firstOrNull { (_, track) -> track.getString(MediaFormat.KEY_MIME)?.startsWith(mimeType) == true } ?: return null
        return Triple(mediaExtractor, index, track)
    }

}