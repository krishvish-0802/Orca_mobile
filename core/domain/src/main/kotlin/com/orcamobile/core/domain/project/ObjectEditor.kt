package com.orcamobile.core.domain.project

import com.orcamobile.core.model.ObjectEdit
import com.orcamobile.core.model.ObjectUpdate
import com.orcamobile.core.model.ProjectId
import com.orcamobile.core.model.SettingScope

/**
 * The desktop's object list, run by the engine on a loaded project: an object's
 * parts, modifiers and other volumes, its own settings and its height ranges.
 */
interface ObjectEditor {
    suspend fun details(project: ProjectId, objectIndex: Int): Result<ObjectUpdate>

    /** Applies [edit] and returns the object after it. */
    suspend fun edit(project: ProjectId, objectIndex: Int, edit: ObjectEdit): Result<ObjectUpdate>

    /** The setting keys a target of [scope] takes. */
    suspend fun settingKeys(scope: SettingScope): Result<List<String>>
}
