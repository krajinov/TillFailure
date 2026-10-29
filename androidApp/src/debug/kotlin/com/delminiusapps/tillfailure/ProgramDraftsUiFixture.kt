package com.delminiusapps.tillfailure

import android.content.Context
import com.delminiusapps.tillfailure.firebase.AndroidProductIdentitySession
import com.delminiusapps.tillfailure.firebase.FirebaseEmulatorConfiguration
import org.json.JSONObject

/** Debug-only authentication bootstrap. It never injects editor state or submits a program. */
object ProgramDraftsUiFixture {
    @JvmStatic fun prepare(context:Context,configuration:FirebaseEmulatorConfiguration,prefix:String) {
        require(configuration.projectId=="demo-tillfailure-m3" && configuration.host=="10.0.2.2" && Regex("m6p3r[0-9a-z]{6,24}").matches(prefix))
        val file=java.io.File(context.filesDir,"m6p3-ui-fixture.json")
        require(file.isFile && file.length()<=4096)
        val data=JSONObject(file.readText());val uid="${prefix}_android_ui"
        require(data.keys().asSequence().toSet()==setOf("uid","password") && data.getString("uid")==uid)
        val client=AndroidProductIdentitySession.get(context,configuration)
        if(client.hasPinnedIdentity())return
        client.signIn("$uid@example.invalid",data.getString("password"),0){result->
            println("M6P3_ANDROID_UI_AUTH=${if(result==null)"PASS" else "FAIL"}")
        }
    }
}
