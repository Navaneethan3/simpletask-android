package nl.mpcjanssen.simpletask.remote;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.*;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.os.AsyncTask;
import android.support.v4.content.LocalBroadcastManager;
import android.util.Log;
import com.dropbox.client2.DropboxAPI;
import com.dropbox.client2.RESTUtility;
import com.dropbox.client2.android.AndroidAuthSession;
import com.dropbox.client2.exception.DropboxException;
import com.dropbox.client2.jsonextract.JsonExtractionException;
import com.dropbox.client2.jsonextract.JsonMap;
import com.dropbox.client2.jsonextract.JsonThing;
import com.dropbox.client2.session.AppKeyPair;
import nl.mpcjanssen.simpletask.Constants;
import nl.mpcjanssen.simpletask.R;
import nl.mpcjanssen.simpletask.task.Task;
import nl.mpcjanssen.simpletask.task.TodoList;
import nl.mpcjanssen.simpletask.util.ListenerList;
import nl.mpcjanssen.simpletask.util.Util;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.FutureTask;

/**
 * FileStore implementation backed by Dropbox
 */
public class FileStore implements FileStoreInterface {

    private final String TAG = getClass().getName();
    private final FileChangeListener mFileChangedListerer;
    private final Context mCtx;
    private  SharedPreferences mPrefs;
    private String mEol;
    // In the class declaration section:
    private DropboxAPI<AndroidAuthSession> mDBApi;
    private String mWatchedFile;
    private Thread pollingTask;
    private String latestCursor;

    private static String LOCAL_CONTENTS = "localContents";
    private static String LOCAL_NAME = "localName";
    private static String LOCAL_REVISION = "localRev";
    private static String CACHE_PREFS = "dropboxMeta";
    private static String OAUTH2_TOKEN = "dropboxToken";


    private String loadContentsFromCache() {
        if (mPrefs == null) {
            return "";
        }
       return  mPrefs.getString(LOCAL_CONTENTS, "");
    }

    private void saveToCache(@NotNull DropboxAPI.Entry metaData, @NotNull String contents) {
        Log.v(TAG, "Storing rev: " + metaData.rev + " of file: " + metaData.fileName());
        if (mPrefs == null) {

            return ;
        }
        SharedPreferences.Editor edit = mPrefs.edit();
        edit.putString(LOCAL_NAME, metaData.fileName());
        edit.putString(LOCAL_CONTENTS, contents);
        edit.putString(LOCAL_REVISION, metaData.rev);
        edit.commit();
    }

    public FileStore(Context ctx, FileChangeListener fileChangedListener,  String eol) {
        mPrefs = ctx.getSharedPreferences(CACHE_PREFS, Context.MODE_PRIVATE);
        mFileChangedListerer = fileChangedListener;
        mCtx = ctx;
        mEol = eol;
        setMDBApi();
    }

    private String getLocalTodoRev () {
        if (mPrefs==null) {
            return null;
        }
        return mPrefs.getString(LOCAL_REVISION, null);
    }

    private void setLocalTodoRev (String rev) {
        if (mPrefs==null) {
            return;
        }
        mPrefs.edit().putString(LOCAL_REVISION, rev).commit();
    }

    private void setMDBApi() {
        if (mDBApi == null) {
            String app_secret = mCtx.getString(R.string.dropbox_consumer_secret);
            String app_key = mCtx.getString(R.string.dropbox_consumer_key);
            app_key = app_key.replaceFirst("^db-", "");
            // And later in some initialization function:
            AppKeyPair appKeys = new AppKeyPair(app_key, app_secret);
            String savedAuth = mPrefs.getString(OAUTH2_TOKEN, null);
            AndroidAuthSession session = new AndroidAuthSession(appKeys, savedAuth);
            mDBApi = new DropboxAPI<AndroidAuthSession>(session);
        }
    }

    @NotNull
    static public String getDefaultPath() {
        return "/todo/todo.txt";
    }

    @Override
    public boolean isAuthenticated() {
        if (mDBApi == null) {
            return false;
        }
        if (mDBApi.getSession().isLinked()) {
            return true;
        }
        if (mDBApi.getSession().authenticationSuccessful()) {
            try {
                // Required to complete auth, sets the access token on the session
                mDBApi.getSession().finishAuthentication();
                String accessToken = mDBApi.getSession().getOAuth2AccessToken();
                mPrefs.edit().putString(OAUTH2_TOKEN, accessToken).commit();
                return true;
            } catch (IllegalStateException e) {
                Log.i("DbAuthLog", "Error authenticating", e);
            }
        }
        return false;
    }

    @Override
    public TodoList loadTasksFromFile(String path, @Nullable TodoList.TodoListChanged todoListChanged, final @Nullable BackupInterface backup) throws IOException {
        Log.i(TAG, "Loading file fom dropnbox: " + path);
        if (!isAuthenticated()) {
            TodoList result = new TodoList(null);
            return result;
        }

        final TodoList todoList = new TodoList(todoListChanged);

        try {
            DropboxAPI.DropboxInputStream openFileStream = mDBApi.getFileStream(path, null);
            DropboxAPI.DropboxFileInfo fileInfo = openFileStream.getFileInfo();
            Log.i(TAG, "The file's rev is: " + fileInfo.getMetadata().rev);

            BufferedReader reader = new BufferedReader(new InputStreamReader(openFileStream, "UTF-8"));
            String line;
            ArrayList<String> readFile = new ArrayList<>();
            while ((line = reader.readLine()) != null) {
                todoList.add(new Task(line));
                readFile.add(line);
            }
            openFileStream.close();
            String contents =  Util.join(readFile, "\n");
            backup.backup(path, contents);
            saveToCache(fileInfo.getMetadata(),contents);
            startWatching(path);
        } catch (DropboxException e) {
            // Couldn't download file use cached version
            e.printStackTrace();
            String contents = loadContentsFromCache();
            for (String line: contents.split("(\r\n|\r|\n)")) {
                todoList.add(new Task(line));
            }
        } ;

        return todoList;
    }



    @Override
    public void startLogin(Activity caller, int i) {
        // MyActivity below should be your activity class name
       mDBApi.getSession().startOAuth2Authentication(caller);
    }



    private void startWatching(final String path) {
        if (pollingTask==null) {
            pollingTask = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        Thread.sleep(1000 * 60 * 5);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                    mFileChangedListerer.fileChanged();
                    return;
                }
            });
            pollingTask.start();
        }

    }
    

    private void stopWatching() {
        // Not implemented
    }

    @Override
    public void logout() {
        if(mDBApi!=null) {
            mDBApi.getSession().unlink();
        }
        mPrefs.edit().remove(OAUTH2_TOKEN);
    }

    @Override
    public void browseForNewFile(Activity act, String path, FileSelectedListener listener, boolean txtOnly) {
        FileDialog dialog = new FileDialog(act, path , true);
        dialog.addFileListener(listener);
        dialog.createFileDialog(act, this);
    }

    @Override
    public void saveTasksToFile(String path, TodoList todoList, @Nullable final BackupInterface backup) throws IOException {
        if (backup != null) {
            backup.backup(path, Util.joinTasks(todoList.getTasks(), "\n"));
        }
        stopWatching();
        try {
            List<String> lines = Util.tasksToString(todoList);
            // Create raw file contents

            String contents = Util.join(lines, mEol);
            byte[] toStore = new byte[0];

            toStore = contents.getBytes("UTF-8");

            String rev = getLocalTodoRev();
            InputStream in = new ByteArrayInputStream(toStore);

            DropboxAPI.Entry newEntry = mDBApi.putFile(path, in,
                    toStore.length, rev, null);
            setLocalTodoRev(newEntry.rev);
        } catch (Exception e) {
            e.printStackTrace();
            throw new IOException(e);
        }
        startWatching(path);
    }

    @Override
    public void appendTaskToFile(String path, List<Task> tasks) throws IOException {
        try {

            // First read file to append to
            DropboxAPI.DropboxInputStream openFileStream =  mDBApi.getFileStream(path, null);
            DropboxAPI.DropboxFileInfo fileInfo = openFileStream.getFileInfo();
            Log.i(TAG, "The file's rev is: " + fileInfo.getMetadata().rev);
            BufferedReader reader = new BufferedReader(new InputStreamReader(openFileStream, "UTF-8"));
            String line;
            ArrayList<String> doneContents = new ArrayList<>();
            while ((line = reader.readLine()) != null) {
                doneContents.add(line);
            }
            openFileStream.close();

            // Then append
            for (Task t : tasks) {
                doneContents.add(t.inFileFormat());
            }
            byte[] toStore = Util.join(doneContents, mEol).getBytes("UTF-8");
            InputStream in = new ByteArrayInputStream(toStore);

            DropboxAPI.Entry newEntry = mDBApi.putFile(path, in,
                   toStore.length, fileInfo.getMetadata().rev, null);
            in.close();
        } catch (DropboxException e) {
            throw new IOException(e);
        }
    }


    @Override
    public void setEol(String eol) {
        mEol = eol;
    }

    @Override
    public void sync() {
        mFileChangedListerer.fileChanged();
    }

    @Override
    public String readFile(String path) throws IOException {
        if (!isAuthenticated()) {
            return "";
        }
        try {
            DropboxAPI.DropboxInputStream openFileStream = mDBApi.getFileStream(path, null);
            DropboxAPI.DropboxFileInfo fileInfo = openFileStream.getFileInfo();
            Log.i(TAG, "The file's rev is: " + fileInfo.getMetadata().rev);

            BufferedReader reader = new BufferedReader(new InputStreamReader(openFileStream, "UTF-8"));
            String line;
            ArrayList<String> readFile = new ArrayList<>();
            while ((line = reader.readLine()) != null) {
                readFile.add(line);
            }
            openFileStream.close();
            return Util.join(readFile,"\n");
        } catch (DropboxException e) {
            throw (new IOException(e));
        }
    }

    @Override
    public boolean supportsSync() {
        return true;
    }

    @Override
    public int getType() {
        return Constants.STORE_DROPBOX;
    }

    public void changedConnectionState(boolean connected) {
        if (!connected) {
            stopWatching();
        } else {

            mFileChangedListerer.fileChanged();
        }
    }

    public static class FileDialog {
        private static final String PARENT_DIR = "..";
        private String[] fileList;
        private HashMap<String,DropboxAPI.Entry> entryHash = new HashMap<>();
        private File currentPath;

        private ListenerList<FileSelectedListener> fileListenerList = new ListenerList<FileSelectedListener>();
        private final Activity activity;
        private boolean txtOnly;
        Dialog dialog;
        private Dialog loadingOverlay;


        /**
         * @param activity
         * @param pathName
         */
        public FileDialog(Activity activity, String pathName, boolean txtOnly) {
            this.activity = activity;
            this.txtOnly=txtOnly;
            currentPath = new File(pathName);

        }

        /**
         *
         */
        public void createFileDialog(final Activity act, final FileStoreInterface fs) {
            loadingOverlay = Util.showLoadingOverlay(act, null, true);

            final DropboxAPI<AndroidAuthSession> api = ((FileStore)fs).mDBApi;
            if (api==null) {
                return;
            }

            // Use an asynctask because we need to manage the UI
            new AsyncTask<Void,Void, AlertDialog.Builder>() {
                @Override
                protected AlertDialog.Builder doInBackground(Void... params) {

                    loadFileList(act, api, currentPath);
                    AlertDialog.Builder builder = new AlertDialog.Builder(activity);
                    builder.setTitle(currentPath.getPath());

                    builder.setItems(fileList, new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface dialog, int which) {
                            String fileChosen = fileList[which];
                            if (fileChosen.equals(PARENT_DIR)) {
                                currentPath = new File(currentPath.getParent());
                                createFileDialog(act, fs);
                                return;
                            }
                            File chosenFile = getChosenFile(fileChosen);
                            Log.w("FileStore", "Selected file " + chosenFile.getName());
                            DropboxAPI.Entry entry = entryHash.get(fileChosen);
                            if (entry.isDir) {
                                currentPath = chosenFile;
                                createFileDialog(act, fs);
                            } else {
                                dialog.cancel();
                                dialog.dismiss();
                                fireFileSelectedEvent(chosenFile);
                            }
                        }
                    });
                    return builder;
                }

                @Override
                protected void onPostExecute(AlertDialog.Builder builder) {
                    loadingOverlay = Util.showLoadingOverlay(act, loadingOverlay, false);
                    if (dialog!=null) {
                        dialog.cancel();
                        dialog.dismiss();
                    }
                    dialog = builder.create();
                    dialog.show();
                }

            }.execute();
        }


        public void addFileListener(FileSelectedListener listener) {
            fileListenerList.add(listener);
        }


        private void fireFileSelectedEvent(final File file) {
            fileListenerList.fireEvent(new ListenerList.FireHandler<FileSelectedListener>() {
                public void fireEvent(FileSelectedListener listener) {
                    listener.fileSelected(file.toString());
                }
            });
        }

        private DropboxAPI.Entry getPathMetaData(DropboxAPI api, File path) throws DropboxException {
            if (api!=null) {
                return api.metadata(path.toString(), 0, null, true, null);
            } else {
                return null;
            }
        }

        private void loadFileList(Activity act, DropboxAPI<AndroidAuthSession> api, File path) {
            if (path==null) {
                path = new File("/");
            }

            this.currentPath = path;
            List<String> f = new ArrayList<String>();
            List<String> d = new ArrayList<String>();

            try {
                DropboxAPI.Entry entries = getPathMetaData(api,path) ;
                entryHash.clear();
                if (!entries.isDir) return;
                if (!path.toString().equals("/")) {
                    d.add(PARENT_DIR);
                }
                for (DropboxAPI.Entry entry : entries.contents) {
                    if (entry.isDeleted) continue;
                    if (entry.isDir) {
                        d.add(entry.fileName());
                    } else {
                        f.add(entry.fileName());
                    }
                    entryHash.put(entry.fileName(), entry);
                }
            } catch (DropboxException e) {
                Log.w("FileStore", "Couldn't load list from " + path.getName() + " loading root instead.");
                loadFileList(act, api, null);
                return;
            }
            Collections.sort(d);
            Collections.sort(f);
            d.addAll(f);
            fileList = d.toArray(new String[d.size()]);
        }

        private File getChosenFile(String fileChosen) {
            if (fileChosen.equals(PARENT_DIR)) return currentPath.getParentFile();
            else return new File(currentPath, fileChosen);
        }
    }
}
