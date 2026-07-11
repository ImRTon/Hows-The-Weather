package com.rton.howstheweather.cloud

import android.graphics.Bitmap
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc
import org.opencv.video.Video

class OpenCvCloudMotionEstimator : CloudMotionEstimator {
    override fun extrapolate(previous: Bitmap, current: Bitmap, frameCount: Int): List<Bitmap> {
        require(previous.width == current.width && previous.height == current.height)
        require(frameCount in 1..12)
        val previousRgba = Mat()
        val currentRgba = Mat()
        val previousGray = Mat()
        val currentGray = Mat()
        val flow = Mat()
        Utils.bitmapToMat(previous, previousRgba)
        Utils.bitmapToMat(current, currentRgba)
        Imgproc.cvtColor(previousRgba, previousGray, Imgproc.COLOR_RGBA2GRAY)
        Imgproc.cvtColor(currentRgba, currentGray, Imgproc.COLOR_RGBA2GRAY)
        Video.calcOpticalFlowFarneback(previousGray, currentGray, flow, 0.5, 3, 15, 3, 5, 1.2, 0)
        val channels = mutableListOf<Mat>()
        Core.split(flow, channels)
        val flowX = channels[0]
        val flowY = channels[1]

        val frames = (1..frameCount).map { step ->
            val mapX = Mat(current.height, current.width, CvType.CV_32FC1)
            val mapY = Mat(current.height, current.width, CvType.CV_32FC1)
            val factor = step.toDouble()
            for (y in 0 until current.height) for (x in 0 until current.width) {
                val dx = flowX.get(y, x)[0]
                val dy = flowY.get(y, x)[0]
                mapX.put(y, x, x - dx * factor)
                mapY.put(y, x, y - dy * factor)
            }
            val warped = Mat()
            Imgproc.remap(
                currentRgba, warped, mapX, mapY,
                Imgproc.INTER_LINEAR, Core.BORDER_TRANSPARENT, Scalar(0.0, 0.0, 0.0, 0.0),
            )
            Bitmap.createBitmap(current.width, current.height, Bitmap.Config.ARGB_8888).also {
                Utils.matToBitmap(warped, it)
                warped.release(); mapX.release(); mapY.release()
            }
        }
        previousRgba.release(); currentRgba.release(); previousGray.release(); currentGray.release(); flow.release()
        channels.forEach(Mat::release)
        return frames
    }
}
