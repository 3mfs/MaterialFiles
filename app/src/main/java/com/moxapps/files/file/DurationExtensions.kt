package com.moxapps.files.file

import android.text.format.DateUtils
import java.time.Duration

fun Duration.format(): String = DateUtils.formatElapsedTime(seconds)
