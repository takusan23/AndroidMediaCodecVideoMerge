package io.github.takusan23.androidmediacodecvideomerge

import android.media.*
import java.io.File

/**
 * 音声データを結合する
 *
 * @param videoList 結合する動画、音声ファイルの配列。入っている順番どおりに結合します。
 * @param mergeFilePath 結合したファイルの保存先
 * @param tempRawDataFile 一時的ファイル保存先
 * @param bitRate ビットレート。なんかゴミみたいな音質だった...
 * */
class AudioDataMerge(
    videoList: List<File>,
    private val mergeFilePath: File,
    private val tempRawDataFile: File,
    private val bitRate: Int = 192_000,
) {

    /** タイムアウト */
    private val TIMEOUT_US = 10000L

    /** MediaCodecでもらえるInputBufferのサイズ */
    private val INPUT_BUFFER_SIZE = 655360

    /** 結合する動画の配列のイテレータ */
    private val videoListIterator = videoList.listIterator()

    /** 一時ファイル保存で使う */
    private val bufferedOutputStream by lazy { tempRawDataFile.outputStream().buffered() }

    /** 一時ファイル読み出しで使う */
    private val bufferedInputStream by lazy { tempRawDataFile.inputStream().buffered() }

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
            val (_mediaExtractor, index, format) = extractMedia(path, "audio/") ?: return
            currentMediaExtractor = _mediaExtractor
            currentMediaFormat = format
            // 音声のトラックを選択
            currentMediaExtractor?.selectTrack(index)
        }

        // 最初の動画を解析
        extractVideoFile(videoListIterator.next().path)

        // 解析結果から各パラメータを取り出す
        val mimeType = currentMediaFormat?.getString(MediaFormat.KEY_MIME)!! // AACなら audio/mp4a-latm
        val samplingRate = currentMediaFormat?.getInteger(MediaFormat.KEY_SAMPLE_RATE)!! // 44100
        val channelCount = currentMediaFormat?.getInteger(MediaFormat.KEY_CHANNEL_COUNT)!! // 2

        // エンコーダーにセットするMediaFormat
        val audioMediaFormat = MediaFormat.createAudioFormat(mimeType, samplingRate, channelCount).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, INPUT_BUFFER_SIZE)
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
        }

        // 音声を追加してトラック番号をもらう
        // 多分 addTrack する際は MediaExtractor 経由で取得した MediaFormat を入れないといけない？
        val audioTrackIndex = mediaMuxer.addTrack(currentMediaFormat!!)

        // デコード用（aac -> 生データ）MediaCodec
        decodeMediaCodec = MediaCodec.createDecoderByType(mimeType).apply {
            // デコード時は MediaExtractor の MediaFormat で良さそう
            configure(currentMediaFormat!!, null, null, 0)
        }
        // エンコード用（生データ -> aac）MediaCodec
        encodeMediaCodec = MediaCodec.createEncoderByType(mimeType).apply {
            configure(audioMediaFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        }

        // nonNull
        val decodeMediaCodec = decodeMediaCodec!!
        val encodeMediaCodec = encodeMediaCodec!!
        // スタート
        decodeMediaCodec.start()
        encodeMediaCodec.start()
        mediaMuxer.start()

        // 再生位置など
        val bufferInfo = MediaCodec.BufferInfo()

        /**
         * データを順次読み出して、[MediaCodec]で生データへ変換する。
         * 変換した生データは[tempRawDataFile]へ一時的に入れる。
         * */
        var totalPresentationTime = 0L
        var prevPresentationTime = 0L
        while (true) {
            // もし -1 が返ってくれば configure() が間違ってる
            val inputBufferId = decodeMediaCodec.dequeueInputBuffer(TIMEOUT_US)
            if (inputBufferId >= 0) {
                // Extractorからデータを読みだす
                val inputBuffer = decodeMediaCodec.getInputBuffer(inputBufferId)!!
                val size = currentMediaExtractor!!.readSampleData(inputBuffer, 0)
                if (size > 0) {
                    // デコーダーへ流す
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
                        // データなくなった場合は終了フラグを立てる
                        decodeMediaCodec.queueInputBuffer(inputBufferId, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        // 開放
                        currentMediaExtractor!!.release()
                        // 終了
                        break
                    }
                }
            }

            /**
             * デコード結果を受け取って、一時的に保存する
             * */
            val outputBufferId = decodeMediaCodec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
            if (outputBufferId >= 0) {
                // デコード結果をもらう
                val outputBuffer = decodeMediaCodec.getOutputBuffer(outputBufferId)!!
                // 生データを一時的に保存する
                val chunk = ByteArray(bufferInfo.size)
                outputBuffer[chunk]
                bufferedOutputStream.write(chunk)
                // 消したほうがいいらしい
                outputBuffer.clear()
                // 返却
                decodeMediaCodec.releaseOutputBuffer(outputBufferId, false)
            }
        }

        // Xiaomi端末で落ちたので例外処理
        try {
            // デコーダー終了
            decodeMediaCodec.stop()
            decodeMediaCodec.release()
            bufferedOutputStream.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // 読み出し済みの位置と時間
        var totalBytesRead = 0
        var presentationTime = 0L

        /**
         * 一時的に保存したファイルを読み出して、エンコーダーに入れる。
         * エンコード結果を[MediaMuxer]へ入れて完成。
         * */
        while (true) {
            val inputBufferId = encodeMediaCodec.dequeueInputBuffer(TIMEOUT_US)
            if (inputBufferId >= 0) {
                // デコードした生データをエンコーダーへ渡す
                val inputBuffer = encodeMediaCodec.getInputBuffer(inputBufferId)!!
                val buffer = ByteArray(inputBuffer.capacity())
                val size = bufferedInputStream.read(buffer)
                // エンコーダーへ渡す
                if (size > 0) {
                    // 書き込む。書き込んだデータは[onOutputBufferAvailable]で受け取れる
                    inputBuffer.put(buffer, 0, size)
                    encodeMediaCodec.queueInputBuffer(inputBufferId, 0, size, presentationTime, 0)
                    totalBytesRead += size
                    // あんまり分からん
                    presentationTime = 1000000L * (totalBytesRead / (channelCount * 2)) / samplingRate
                } else {
                    // 終了
                    encodeMediaCodec.queueInputBuffer(inputBufferId, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                }
            }
            // デコーダーから生データを受け取る
            val outputBufferId = encodeMediaCodec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
            if (outputBufferId >= 0) {
                // デコード結果をもらう
                val outputBuffer = encodeMediaCodec.getOutputBuffer(outputBufferId)!!
                if (bufferInfo.size > 0) {
                    // 書き込む
                    mediaMuxer.writeSampleData(audioTrackIndex, outputBuffer, bufferInfo)
                    // 返却
                    encodeMediaCodec.releaseOutputBuffer(outputBufferId, false)
                } else {
                    // もう無い！
                    break
                }
            }
        }

        // Xiaomi端末で落ちたので例外処理
        try {
            // エンコーダー終了
            encodeMediaCodec.stop()
            encodeMediaCodec.release()
            bufferedInputStream.close()

            // MediaMuxerも終了
            // MediaMuxer#stopでコケる場合、大体MediaFormatのパラメータ不足です。
            // MediaExtractorで出てきたFormatを入れると直ると思います。
            mediaMuxer.stop()
            mediaMuxer.release()

            // 一時ファイルの削除
            tempRawDataFile.delete()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /** 強制終了時に呼ぶ */
    fun stop() {
        decodeMediaCodec?.stop()
        decodeMediaCodec?.release()
        bufferedOutputStream.close()
        encodeMediaCodec?.stop()
        encodeMediaCodec?.release()
        bufferedInputStream.close()
        currentMediaExtractor?.release()
        mediaMuxer.stop()
        mediaMuxer.release()
        tempRawDataFile.delete()
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