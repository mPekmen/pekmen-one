package net.pekmen.one.core.platform.planner

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.database.sqlite.SQLiteException
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import net.pekmen.one.core.model.CalendarReadResult
import net.pekmen.one.core.model.ExternalCalendarItem
import net.pekmen.one.core.platform.R

class CalendarInstancesReader(
    private val context: Context
) {

    fun hasReadCalendarPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_CALENDAR
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun readInstances(beginWallMs: Long, endWallMs: Long): CalendarReadResult {
        if (!hasReadCalendarPermission()) {
            return CalendarReadResult.PermissionDenied
        }
        if (endWallMs < beginWallMs) {
            return CalendarReadResult.Available(emptyList())
        }

        return try {
            val events = queryInstances(beginWallMs, endWallMs)
            CalendarReadResult.Available(events)
        } catch (_: SecurityException) {
            CalendarReadResult.PermissionDenied
        } catch (_: IllegalArgumentException) {
            CalendarReadResult.Available(emptyList())
        } catch (_: SQLiteException) {
            CalendarReadResult.Available(emptyList())
        }
    }

    private fun queryInstances(beginWallMs: Long, endWallMs: Long): List<ExternalCalendarItem> {
        val uriBuilder = CalendarContract.Instances.CONTENT_URI.buildUpon()
        ContentUris.appendId(uriBuilder, beginWallMs)
        ContentUris.appendId(uriBuilder, endWallMs)
        val uri = uriBuilder.build()

        val cursor = context.contentResolver.query(
            uri,
            INSTANCE_PROJECTION,
            null,
            null,
            "${CalendarContract.Instances.BEGIN} ASC"
        ) ?: return emptyList()

        return cursor.use { activeCursor ->
            extractItems(activeCursor)
        }
    }

    private fun extractItems(cursor: Cursor): List<ExternalCalendarItem> {
        val idIdx = cursor.getColumnIndex(CalendarContract.Instances.EVENT_ID)
        val titleIdx = cursor.getColumnIndex(CalendarContract.Instances.TITLE)
        val beginIdx = cursor.getColumnIndex(CalendarContract.Instances.BEGIN)
        val endIdx = cursor.getColumnIndex(CalendarContract.Instances.END)
        val allDayIdx = cursor.getColumnIndex(CalendarContract.Instances.ALL_DAY)
        val calNameIdx = cursor.getColumnIndex(CalendarContract.Instances.CALENDAR_DISPLAY_NAME)

        if (beginIdx < 0) return emptyList()

        val untitled = context.getString(R.string.planner_untitled_calendar_event)
        val items = mutableListOf<ExternalCalendarItem>()
        while (cursor.moveToNext()) {
            val eventId = if (idIdx >= 0 && !cursor.isNull(idIdx)) {
                cursor.getLong(idIdx)
            } else {
                items.size.toLong()
            }
            val rawTitle = if (titleIdx >= 0 && !cursor.isNull(titleIdx)) {
                cursor.getString(titleIdx)
            } else {
                null
            }
            val beginMs = cursor.getLong(beginIdx)
            val endMs = if (endIdx >= 0 && !cursor.isNull(endIdx)) {
                cursor.getLong(endIdx).coerceAtLeast(beginMs)
            } else {
                beginMs
            }
            val allDay = allDayIdx >= 0 && !cursor.isNull(allDayIdx) && cursor.getInt(allDayIdx) != 0
            val calendarName = if (calNameIdx >= 0 && !cursor.isNull(calNameIdx)) {
                cursor.getString(calNameIdx)?.trim()?.ifEmpty { null }
            } else {
                null
            }
            items.add(
                ExternalCalendarItem(
                    eventId = eventId,
                    title = rawTitle?.trim()?.ifEmpty { null } ?: untitled,
                    beginWallMs = beginMs,
                    endWallMs = endMs,
                    allDay = allDay,
                    calendarDisplayName = calendarName
                )
            )
        }
        return items.sortedBy { it.beginWallMs }
    }

    private companion object {
        val INSTANCE_PROJECTION = arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.CALENDAR_DISPLAY_NAME
        )
    }
}
