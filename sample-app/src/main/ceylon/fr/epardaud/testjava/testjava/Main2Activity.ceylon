import android.app { ListActivity }
import android.support.v7.app { AppCompatActivity }
import android.os { Bundle }
import android.widget { ArrayAdapter, ListAdapter }
import android { AndroidR = R }
import ceylon.interop.java { createJavaStringArray }
import java.lang { JString = String }
import ceylon.language.meta { modules }

shared class Main2Activity() extends ListActivity() {

    shared actual void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.Layout.activity_main2);

        ListAdapter adapter = ArrayAdapter<JString>(this, AndroidR.Layout.simple_list_item_1,
                createJavaStringArray { for (mod in modules.list) mod.string });
        listAdapter = adapter;
    }
}
