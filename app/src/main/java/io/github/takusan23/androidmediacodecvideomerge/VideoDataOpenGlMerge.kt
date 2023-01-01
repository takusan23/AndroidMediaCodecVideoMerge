package io.github.takusan23.androidmediacodecvideomerge

import android.media.*
import io.github.takusan23.androidmediacodecvideomerge.gl.CodecInputSurface
import java.io.File

/**
 * OpenGLを利用した[VideoDataMerge]
 *
 * 動画の幅とかを変えられます...
 *
 * @param videoList 結合する動画、音声ファイルの配列。入っている順番どおりに結合します。
 * @param resultFile 結合したファイルの保存
 * @param bitRate ビットレート。何故か取れなかった
 * @param frameRate フレームレート。何故か取れなかった
 * @param videoHeight 動画の高さを帰る場合は変えられます。16の倍数であることが必須です
 * @param videoWidth 動画の幅を変える場合は変えられます。16の倍数であることが必須です
 * */
class VideoDataOpenGlMerge(
    videoList: List<File>,
    private val resultFile: File,
    private val bitRate: Int = 1_000_000, // 1Mbps
    private val frameRate: Int = 30, // 30fps
    private val videoWidth: Int = 1280,
    private val videoHeight: Int = 720,
) {
    /** タイムアウト */
    private val TIMEOUT_US = 10000L

    /** MediaCodecでもらえるInputBufferのサイズ */
    private val INPUT_BUFFER_SIZE = 655360

    /** 結合する動画の配列のイテレータ */
    private val videoListIterator = videoList.listIterator()

    /** ファイル合成 */
    private val mediaMuxer by lazy { MediaMuxer(resultFile.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4) }

    /** 取り出した[MediaFormat] */
    private var currentMediaFormat: MediaFormat? = null

    /** 現在進行中の[MediaExtractor] */
    private var currentMediaExtractor: MediaExtractor? = null

    /** エンコード用 [MediaCodec] */
    private var encodeMediaCodec: MediaCodec? = null

    /** デコード用 [MediaCodec] */
    private var decodeMediaCodec: MediaCodec? = null

    /** OpenGL */
    private var codecInputSurface: CodecInputSurface? = null

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
            currentMediaExtractor?.seekTo(0, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        }

        // 最初の動画を解析
        extractVideoFile(videoListIterator.next().path)

        // 解析結果から各パラメータを取り出す
        val mimeType = currentMediaFormat?.getString(MediaFormat.KEY_MIME)!! // video/avc

        // エンコーダーにセットするMediaFormat
        val videoMediaFormat = MediaFormat.createVideoFormat(mimeType, videoWidth, videoHeight).apply {
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, INPUT_BUFFER_SIZE)
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
        }

        var videoTrackIndex = NO_INDEX_VALUE

        // エンコード用（生データ -> H.264）MediaCodec
        encodeMediaCodec = MediaCodec.createEncoderByType(mimeType).apply {
            configure(videoMediaFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        }

        // エンコーダーのSurfaceを取得
        // デコーダーの出力Surfaceの項目にこれを指定して、エンコーダーに映像データがSurface経由で行くようにする
        // なんだけど、直接Surfaceを渡すだけではなくなんかOpenGLを利用しないと正しく描画できないみたい
        // https://github.com/zolad/VideoSlimmer
        codecInputSurface = CodecInputSurface(encodeMediaCodec!!.createInputSurface())
        codecInputSurface?.makeCurrent()
        encodeMediaCodec!!.start()

        // デコード用（H.264 -> 生データ）MediaCodec
        codecInputSurface?.createRender()
        decodeMediaCodec = MediaCodec.createDecoderByType(mimeType).apply {
            // デコード時は MediaExtractor の MediaFormat で良さそう
            configure(currentMediaFormat!!, codecInputSurface!!.surface, null, 0)
        }
        decodeMediaCodec?.start()

        // nonNull
        val decodeMediaCodec = decodeMediaCodec!!
        val encodeMediaCodec = encodeMediaCodec!!

        println("""
            エンコーダー：${encodeMediaCodec.name}
            デコーダー：${decodeMediaCodec.name}
        """.trimIndent())

        /**
         *  --- 複数ファイルを全てデコードする ---
         * */
        var totalPresentationTime = 0L
        var prevPresentationTime = 0L

        // メタデータ格納用
        val bufferInfo = MediaCodec.BufferInfo()

        var outputDone = false
        var inputDone = false

        var prevTimeSec = 0L

        while (!outputDone) {
            if (!inputDone) {

                val inputBufferId = decodeMediaCodec.dequeueInputBuffer(TIMEOUT_US)
                if (inputBufferId >= 0) {
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

                        val calcSec = prevPresentationTime / 1000 / 1000
                        if (prevTimeSec != calcSec) {
                          //  println(calcSec)
                        }
                        prevTimeSec = calcSec

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
                            inputDone = true
                        }
                    }
                }
            }
            var decoderOutputAvailable = true
            while (decoderOutputAvailable) {
                // Surface経由でデータを貰って保存する
                val encoderStatus = encodeMediaCodec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                if (encoderStatus >= 0) {
                    val encodedData = encodeMediaCodec.getOutputBuffer(encoderStatus)!!
                    if (bufferInfo.size > 1) {
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                            mediaMuxer.writeSampleData(videoTrackIndex, encodedData, bufferInfo)
                        } else if (videoTrackIndex == NO_INDEX_VALUE) {
                            // MediaMuxerへ映像トラックを追加するのはこのタイミングで行う
                            // このタイミングでやると固有のパラメーターがセットされたMediaFormatが手に入る(csd-0 とか)
                            // 映像がぶっ壊れている場合（緑で塗りつぶされてるとか）は多分このあたりが怪しい
                            val newFormat = encodeMediaCodec.outputFormat
                            videoTrackIndex = mediaMuxer.addTrack(newFormat)
                            mediaMuxer.start()
                        }
                    }
                    outputDone = bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    encodeMediaCodec.releaseOutputBuffer(encoderStatus, false)
                }
                if (encoderStatus != MediaCodec.INFO_TRY_AGAIN_LATER) {
                    continue
                }
                // Surfaceへレンダリングする。そしてOpenGLでゴニョゴニョする
                val outputBufferId = decodeMediaCodec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                if (outputBufferId == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    decoderOutputAvailable = false
                } else if (outputBufferId >= 0) {
                    val doRender = bufferInfo.size != 0
                    decodeMediaCodec.releaseOutputBuffer(outputBufferId, doRender)
                    if (doRender) {
                        var errorWait = false
                        try {
                            codecInputSurface?.awaitNewImage()
                        } catch (e: Exception) {
                            errorWait = true
                        }
                        if (!errorWait) {
                            codecInputSurface?.drawImage()
                            codecInputSurface?.setPresentationTime(bufferInfo.presentationTimeUs * 1000)
                            codecInputSurface?.swapBuffers()
                        }
                    }
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        decoderOutputAvailable = false
                        encodeMediaCodec.signalEndOfInputStream()
                    }
                }
            }
        }

        // Xiaomi端末で落ちたので例外処理
        try {
            // デコーダー終了
            decodeMediaCodec.stop()
            decodeMediaCodec.release()
            // OpenGL開放
            codecInputSurface?.release()
            // エンコーダー終了
            encodeMediaCodec.stop()
            encodeMediaCodec.release()
            // MediaMuxerも終了
            mediaMuxer.stop()
            mediaMuxer.release()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /** 強制終了時に呼ぶ */
    fun stop() {
        decodeMediaCodec?.stop()
        decodeMediaCodec?.release()
        codecInputSurface?.release()
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
        val (index, track) = (0 until mediaExtractor.trackCount)
            .map { index -> index to mediaExtractor.getTrackFormat(index) }
            .firstOrNull { (_, track) -> track.getString(MediaFormat.KEY_MIME)?.startsWith(mimeType) == true } ?: return null
        return Triple(mediaExtractor, index, track)
    }

    companion object {

        /** トラック番号が空の場合 */
        const val NO_INDEX_VALUE = -100

    }

}