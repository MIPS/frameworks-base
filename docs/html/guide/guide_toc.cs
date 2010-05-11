<?cs # Table of contents for Dev Guide. 

       For each document available in translation, add an localized title to this TOC. 
       Do not add localized title for docs not available in translation.
       Below are template spans for adding localized doc titles. Please ensure that
       localized titles are added in the language order specified below.
?>
<ul>

  <li>
    <h2><span class="en">Android Basics</span>
        <span class="de" style="display:none">Einführung in Android</span>
        <span class="es" style="display:none">Información básica sobre Android</span>
        <span class="fr" style="display:none">Présentation d'Android</span>
        <span class="it" style="display:none">Nozioni di base su Android</span>
        <span class="ja" style="display:none">Android の基本</span>
        <span class="zh-CN" style="display:none">Android 基础知识</span>
        <span class="zh-TW" style="display:none">Android 簡介</span>
    </h2>
    <ul>
      <li><a href="<?cs var:toroot ?>guide/basics/what-is-android.html">
        <span class="en">What Is Android?</span>
        <span class="de" style="display:none">Was ist Android?</span>
        <span class="es" style="display:none">¿Qué es Android?</span>
        <span class="fr" style="display:none">Qu'est-ce qu'Android&nbsp;?</span>
        <span class="it" style="display:none">Che cos'è Android?</span>
        <span class="ja" style="display:none">Android とは</span>
        <span class="zh-CN" style="display:none">Android 是什么？</span>
        <span class="zh-TW" style="display:none">什麼是 Android？</span>
          </a></li>

  <!--  <li><a style="color:gray;">The Android SDK</a></li> -->
  <!--  <li><a style="color:gray;">Walkthrough for Developers</a></li> -->
      <!-- quick overview of what it's like to develop on Android -->
    </ul>
  </li>
  
  <li>
    <h2>
      <span class="en">Framework Topics</span>
      <span class="de" style="display:none">Framework-Themen</span>
      <span class="es" style="display:none">Temas sobre el framework</span>
      <span class="fr" style="display:none">Thèmes relatifs au framework</span>
      <span class="it" style="display:none">Argomenti relativi al framework</span>
      <span class="ja" style="display:none">フレームワーク トピック</span>
      <span class="zh-CN" style="display:none">框架主题</span>
      <span class="zh-TW" style="display:none">架構主題</span>
    </h2>
    <ul>
      <li><a href="<?cs var:toroot ?>guide/topics/fundamentals.html">
            <span class="en">Application Fundamentals</span>
            <span class="de" style="display:none">Anwendungsgrundlagen</span>
            <span class="es" style="display:none">Fundamentos de las aplicaciones</span>
            <span class="fr" style="display:none">Principes de base des applications</span>
            <span class="it" style="display:none">Concetti fondamentali sulle applicazioni</span>
            <span class="ja" style="display:none">開発の基礎</span>
            <span class="zh-CN" style="display:none">应用程序基础</span>
            <span class="zh-TW" style="display:none">應用程式基本原理</span>

          </a></li>
    </ul>
    <ul>
      <li class="toggle-list">
        <div><a href="<?cs var:toroot ?>guide/topics/ui/index.html">
               <span class="en">User Interface</span>
             </a></div>
        <ul>
          <li><a href="<?cs var:toroot ?>guide/topics/ui/declaring-layout.html">
               <span class="en">Declaring Layout</span> 
              </a></li>
          <li><a href="<?cs var:toroot ?>guide/topics/ui/menus.html">
               <span class="en">Creating Menus</span> 
              </a></li>
          <li><a href="<?cs var:toroot ?>guide/topics/ui/dialogs.html">
                <span class="en">Creating Dialogs</span> 
              </a></li>
          <li><a href="<?cs var:toroot ?>guide/topics/ui/ui-events.html">
                <span class="en">Handling UI Events</span> 
              </a></li>
          <li><a href="<?cs var:toroot ?>guide/topics/ui/notifiers/index.html">
                <span class="en">Notifying the User</span> 
              </a></li>
          <li><a href="<?cs var:toroot ?>guide/topics/ui/themes.html">
                <span class="en">Applying Styles and Themes</span> 
              </a></li>
          <li><a href="<?cs var:toroot ?>guide/topics/ui/custom-components.html">
                <span class="en">Building Custom Components</span> 
              </a></li>
          <li><a href="<?cs var:toroot ?>guide/topics/ui/binding.html">
                <span class="en">Binding to Data with AdapterView</span> 
              </a></li>
          <li><a href="<?cs var:toroot ?>guide/topics/ui/layout-objects.html">
                <span class="en">Common Layout Objects</span> 
              </a></li>
          <li><a href="<?cs var:toroot ?>guide/topics/ui/how-android-draws.html">
                <span class="en">How Android Draws Views</span> 
              </a></li>
        </ul>
      </li>
      <li class="toggle-list">
        <div><a href="<?cs var:toroot ?>guide/topics/resources/index.html">
               <span class="en">Resources and Assets</span> 
             </a></div>
        <ul>
          <li><a href="<?cs var:toroot ?>guide/topics/resources/resources-i18n.html">
                <span class="en">Resources and I18n</span>
              </a></li>
          <li><a href="<?cs var:toroot ?>guide/topics/resources/available-resources.html">
                <span class="en">Available Resource Types</span>
              </a></li>
          <li><a href="<?cs var:toroot ?>guide/topics/resources/localization.html">
                <span class="en">Localization</span>
              </a></li>
        </ul>
      </li>
      <li><a href="<?cs var:toroot ?>guide/topics/intents/intents-filters.html">
            <span class="en">Intents and Intent Filters</span>
          </a></li>
      <li><a href="<?cs var:toroot ?>guide/topics/data/data-storage.html">
            <span class="en">Data Storage</span>
          </a></li>
      <li><a href="<?cs var:toroot ?>guide/topics/providers/content-providers.html">
            <span class="en">Content Providers</span>
          </a></li>
      <li><a href="<?cs var:toroot ?>guide/topics/security/security.html">
            <span class="en">Security and Permissions</span>
          </a></li>
  <!--  <li><a style="color:gray;">Processes and Threads</a></li> -->
  <!--  <li><a style="color:gray;">Interprocess Communication</a></li> -->
      <li class="toggle-list">
        <div><a href="<?cs var:toroot ?>guide/topics/manifest/manifest-intro.html">
               <span class="en">The AndroidManifest.xml File</span>
             </a></div>
        <ul>
          <li><a href="<?cs var:toroot ?>guide/topics/manifest/action-element.html">&lt;action&gt;</a></li>
          <li><a href="<?cs var:toroot ?>guide/topics/manifest/activity-element.html">&lt;activity&gt;</a></li>
          <li><a href="<?cs var:toroot ?>guide/topics/manifest/activity-alias-element.html">&lt;activity-alias&gt;</a></li>
          <li><a href="<?cs var:toroot ?>guide/topics/manifest/application-element.html">&lt;application&gt;</a></li>
          <li><a href="<?cs var:toroot ?>guide/topics/manifest/category-element.html">&lt;category&gt;</a></li>
          <li><a href="<?cs var:toroot ?>guide/topics/manifest/data-element.html">&lt;data&gt;</a></li>
          <li><a href="<?cs var:toroot ?>guide/topics/manifest/grant-uri-permission-element.html">&lt;grant-uri-permission&gt;</a></li>
          <li><a href="<?cs var:toroot ?>guide/topics/manifest/instrumentation-element.html">&lt;instrumentation&gt;</a></li>
          <li><a href="<?cs var:toroot ?>guide/topics/manifest/intent-filter-element.html">&lt;intent-filter&gt;</a></li>
          <li><a href="<?cs var:toroot ?>guide/topics/manifest/manifest-element.html">&lt;manifest&gt;</a></li>
          <li><a href="<?cs var:toroot ?>guide/topics/manifest/meta-data-element.html">&lt;meta-data&gt;</a></li>
          <li><a href="<?cs var:toroot ?>guide/topics/manifest/path-permission-element.html">&lt;path-permission&gt;</a></li>
          <li><a href="<?cs var:toroot ?>guide/topics/manifest/permission-element.html">&lt;permission&gt;</a></li>
          <li><a href="<?cs var:toroot ?>guide/topics/manifest/permission-group-element.html">&lt;permission-group&gt;</a></li>
          <li><a href="<?cs var:toroot ?>guide/topics/manifest/permission-tree-element.html">&lt;permission-tree&gt;</a></li>
          <li><a href="<?cs var:toroot ?>guide/topics/manifest/provider-element.html">&lt;provider&gt;</a></li>
          <li><a href="<?cs var:toroot ?>guide/topics/manifest/receiver-element.html">&lt;receiver&gt;</a></li>
          <li><a href="<?cs var:toroot ?>guide/topics/manifest/service-element.html">&lt;service&gt;</a></li>
          <li><a href="<?cs var:toroot ?>guide/topics/manifest/supports-screens-element.html">&lt;supports-screens&gt;</a></li>  <!-- ##api level 4## -->
          <li><a href="<?cs var:toroot ?>guide/topics/manifest/uses-configuration-element.html">&lt;uses-configuration&gt;</a></li>
          <li><a href="<?cs var:toroot ?>guide/topics/manifest/uses-feature-element.html">&lt;uses-feature&gt;</a></li> <!-- ##api level 4## -->
          <li><a href="<?cs var:toroot ?>guide/topics/manifest/uses-library-element.html">&lt;uses-library&gt;</a></li>
          <li><a href="<?cs var:toroot ?>guide/topics/manifest/uses-permission-element.html">&lt;uses-permission&gt;</a></li>
          <li><a href="<?cs var:toroot ?>guide/topics/manifest/uses-sdk-element.html">&lt;uses-sdk&gt;</a></li>
        </ul>
      </li>
    </ul>
    <ul>
      <li class="toggle-list">
        <div><a href="<?cs var:toroot ?>guide/topics/graphics/index.html">
               <span class="en">Graphics</span>
             </a></div>
        <ul>
          <li><a href="<?cs var:toroot ?>guide/topics/graphics/2d-graphics.html">
                <span class="en">2D Graphics</span>
              </a></li>
          <li><a href="<?cs var:toroot ?>guide/topics/graphics/opengl.html">
                <span class="en">3D with OpenGL</span>
              </a></li>
        </ul>
      </li>
      <li><a href="<?cs var:toroot ?>guide/topics/media/index.html">
            <span class="en">Audio and Video</span>
          </a></li>
  <!--<li class="toggle-list">
        <div><a style="color:gray;">Sensors</a></div>
          <ul>
            <li><a style="color:gray;">Camera</a></li>
            <li><a style="color:gray;">Compass</a></li>
            <li><a style="color:gray;">Accelerometer</a></li>
          </ul>
      </li> -->
      <li><a href="<?cs var:toroot ?>guide/topics/location/index.html">
            <span class="en">Location and Maps</span>
          </a></li>
  <!--<li class="toggle-list">
        <div><a style="color:gray;">Wireless Controls</a></div>
          <ul>
            <li><a style="color:gray;">Wi-Fi</a></li>
          </ul>
      </li> -->
  <!--<li><a style="color:gray;">Localization</a></li>  -->
      <li><a href="<?cs var:toroot ?>guide/topics/appwidgets/index.html">
            <span class="en">App Widgets</span>
          </a></li>
      <li><a href="<?cs var:toroot?>guide/topics/wireless/bluetooth.html">
            <span class="en">Bluetooth</span>
          </a> <span class="new">new!</span></li>
    </ul>
  </li>
  
  <li>
    <h2><span class="en">Developing</span>
               <span class="de" style="display:none">Entwicklung</span>
               <span class="es" style="display:none">Desarrollo</span>
               <span class="fr" style="display:none">Développement</span>
               <span class="it" style="display:none">Sviluppo</span>
               <span class="ja" style="display:none">開発</span>
               <span class="zh-CN" style="display:none">开发</span>
               <span class="zh-TW" style="display:none">開發</span>
    </h2>
    <ul>
  <!--<li><a href="">Developing for Android</a></li>
      signing, upgrading, selecting a package name, select device profile, touch, trackball, dpad available, etc. -->
      <li><a href="<?cs var:toroot ?>guide/developing/eclipse-adt.html">
            <span class="en">In Eclipse, with ADT</span> 
            <span class="de" style="display:none">In Eclipse, mit ADT</span>
            <span class="es" style="display:none">En Eclipse, con ADT</span>
            <span class="fr" style="display:none">Sous Eclipse, à l'aide du plugin ADT</span>
            <span class="it" style="display:none">In Eclipse, con ADT</span>
            <span class="ja" style="display:none">Eclipse 内で ADT を使用</span>
            <span class="zh-CN" style="display:none">利用 ADT 在 Eclipse 中开发</span>
            <span class="zh-TW" style="display:none">在加裝 ADT 工具的 Eclipse 環境中</span>
          </a></li>
      <li><a href="<?cs var:toroot ?>guide/developing/other-ide.html">
            <span class="en">In Other IDEs</span>
            <span class="de" style="display:none">In anderen IDEs</span>
            <span class="es" style="display:none">En otros entornos</span>
            <span class="fr" style="display:none">Sous d'autres environnements</span>
            <span class="it" style="display:none">In altri IDE</span>
            <span class="ja" style="display:none">その他の統合開発環境</span>
            <span class="zh-CN" style="display:none">在其他 IDE 中开发</span>
            <span class="zh-TW" style="display:none">在其他開發環境中</span>
          </a></li>
      <li><a href="<?cs var:toroot ?>guide/developing/device.html">
            <span class="en">On a Device</span>
          </a></li>
      <li><a href="<?cs var:toroot ?>guide/developing/debug-tasks.html">
            <span class="en">Debugging Tasks</span>
          </a></li>
      <li class="toggle-list">
        <div><a href="<?cs var:toroot ?>guide/developing/tools/index.html">
            <span class="en">Tools</span>
          </a></div>
        <ul>
      		<li><a href="<?cs var:toroot ?>guide/developing/tools/aapt.html">aapt</a></li>
      		<li><a href="<?cs var:toroot ?>guide/developing/tools/adb.html">adb</a></li>
      		<li><a href="<?cs var:toroot ?>guide/developing/tools/othertools.html#android">android</a></li>
      <!--<li><a href="<?cs var:toroot ?>guide/developing/tools/adt.html">ADT Plugin</a></li>-->
      		<li><a href="<?cs var:toroot ?>guide/developing/tools/aidl.html">aidl</a></li>
      		<li><a href="<?cs var:toroot ?>guide/developing/tools/avd.html">AVDs</a></li>
      		<li><a href="<?cs var:toroot ?>guide/developing/tools/ddms.html">ddms</a></li>
      		<li><a href="<?cs var:toroot ?>guide/developing/tools/othertools.html#dx">dx</a></li>
      		<li><a href="<?cs var:toroot ?>guide/developing/tools/draw9patch.html">Draw 9-Patch</a></li>
      		<li><a href="<?cs var:toroot ?>guide/developing/tools/emulator.html">Emulator</a></li>
      		<li><a href="<?cs var:toroot ?>guide/developing/tools/hierarchy-viewer.html">Hierarchy Viewer</a></li>
      		<li><a href="<?cs var:toroot ?>guide/developing/tools/layoutopt.html">layoutopt</a></li>
      		<li><a href="<?cs var:toroot ?>guide/developing/tools/othertools.html#mksdcard">mksdcard</a></li>
      		<li><a href="<?cs var:toroot ?>guide/developing/tools/monkey.html">Monkey</a></li>
      		<li><a href="<?cs var:toroot ?>guide/developing/tools/adb.html#sqlite">sqlite3</a></li>
      		<li><a href="<?cs var:toroot ?>guide/developing/tools/traceview.html" >Traceview</a></li>
      		<li><a href="<?cs var:toroot ?>guide/developing/tools/zipalign.html" >zipalign</a></li>
    	  </ul>
  	  </li>
  <!--<li><a href="<?cs var:toroot ?>guide/developing/instrumentation/index.html">Instrumentation</a></li>
      <li><a style="color:gray;">JUnit</a></li> -->
    </ul>
  </li>
  
  <li>
    <h2><span class="en">Publishing</span>
        <span class="de" style="display:none">Veröffentlichung</span>
        <span class="es" style="display:none">Publicación</span>
        <span class="fr" style="display:none">Publication</span>
        <span class="it" style="display:none">Pubblicazione</span>
        <span class="ja" style="display:none">公開</span>
        <span class="zh-CN" style="display:none">发布</span>
        <span class="zh-TW" style="display:none">發佈</span>
    </h2>
    <ul>
      <li><a href="<?cs var:toroot ?>guide/publishing/app-signing.html">
            <span class="en">Signing Your Applications</span>
            <span class="de" style="display:none">Signieren Ihrer Anwendungen</span>
            <span class="es" style="display:none">Firma de aplicaciones</span>
            <span class="fr" style="display:none">Attribution de votre signature <br />à vos applications</span>
            <span class="it" style="display:none">Firma delle applicazioni</span>
            <span class="ja" style="display:none">アプリケーションへの署名</span>
            <span class="zh-CN" style="display:none">应用程序签名</span>
            <span class="zh-TW" style="display:none">簽署應用程式</span>
          </a></li>
      <li><a href="<?cs var:toroot ?>guide/publishing/versioning.html">
            <span class="en">Versioning Your Applications</span>
            <span class="de" style="display:none">Versionsverwaltung für Ihre <br />Anwendungen</span>
            <span class="es" style="display:none">Versiones de las aplicaciones</span>
            <span class="fr" style="display:none">Attribution d'une version à vos applications</span>
            <span class="it" style="display:none">Controllo versioni delle applicazioni</span>
            <span class="ja" style="display:none">アプリケーションのバージョニング</span>
            <span class="zh-CN" style="display:none">应用程序版本控制</span>
            <span class="zh-TW" style="display:none">應用程式版本設定</span>
          </a></li>
      <li><a href="<?cs var:toroot ?>guide/publishing/preparing.html">
            <span class="en">Preparing to Publish</span>
            <span class="de" style="display:none">Vorbereitung auf die Veröffentlichung</span>
            <span class="es" style="display:none">Publicación de aplicaciones</span>
            <span class="fr" style="display:none">Préparation à la publication</span>
            <span class="it" style="display:none">Preparativi per la pubblicazione</span>
            <span class="ja" style="display:none">公開の準備</span>
            <span class="zh-CN" style="display:none">准备发布</span>
            <span class="zh-TW" style="display:none">準備發佈</span>
          </a></li>
      <li><a href="<?cs var:toroot ?>guide/publishing/publishing.html">
            <span class="en">Publishing Your Applications</span>
          </a></li>
    </ul>
  </li>
  
  <li>
    <h2><span class="en">Best Practices</span>
               <span class="de" style="display:none">Bewährte Verfahren</span>
               <span class="es" style="display:none">Prácticas recomendadas</span>
               <span class="fr" style="display:none">Meilleures pratiques</span>
               <span class="it" style="display:none">Best practice</span>
               <span class="ja" style="display:none">ベスト プラクティス</span>
               <span class="zh-CN" style="display:none">最佳实践</span>
               <span class="zh-TW" style="display:none">最佳實務</span>
    </h2>
    <ul>
      <li><a href="<?cs var:toroot ?>guide/practices/screens_support.html">
            <span class="en">Supporting Multiple Screens</span>
          </a></li>
      <li class="toggle-list">
        <div><a href="<?cs var:toroot ?>guide/practices/ui_guidelines/index.html">
               <span class="en">UI Guidelines</span>
             </a></div>
        <ul>
          <li><a href="<?cs var:toroot ?>guide/practices/ui_guidelines/icon_design.html">
                <span class="en">Icon Design</span>
              </a></li>
          <li><a href="<?cs var:toroot ?>guide/practices/ui_guidelines/widget_design.html">
                <span class="en">App Widget Design</span>
              </a></li>
          <li><a href="<?cs var:toroot ?>guide/practices/ui_guidelines/activity_task_design.html">
                <span class="en">Activity and Task Design</span>
              </a></li>
          <li><a href="<?cs var:toroot ?>guide/practices/ui_guidelines/menu_design.html">
                <span class="en">Menu Design</span>
              </a></li>
        </ul>
      </li>
      </ul>
      <ul>
      <li><a href="<?cs var:toroot ?>guide/practices/design/performance.html">
            <span class="en">Designing for Performance</span>
          </a></li>
      <li><a href="<?cs var:toroot ?>guide/practices/design/responsiveness.html">
            <span class="en">Designing for Responsiveness</span>
          </a></li>
      <li><a href="<?cs var:toroot ?>guide/practices/design/seamlessness.html">
            <span class="en">Designing for Seamlessness</span>
          </a></li>
    </ul>
  </li>

  <li>
    <h2><span class="en">Appendix</span>
               <span class="de" style="display:none">Anhang</span>
               <span class="es" style="display:none">Apéndice</span>
               <span class="fr" style="display:none">Annexes</span>
               <span class="it" style="display:none">Appendice</span>
               <span class="ja" style="display:none">付録</span>
               <span class="zh-CN" style="display:none">附录</span>
               <span class="zh-TW" style="display:none">附錄</span>
    </h2>
    <ul>
      <li><a href="<?cs var:toroot ?>guide/appendix/api-levels.html">
            <span class="en">Android API Levels</span>
          </a></li>
      <li><a href="<?cs var:toroot ?>guide/appendix/media-formats.html">
            <span class="en">Supported Media Formats</span>
          </a></li>
      <li><a href="<?cs var:toroot ?>guide/appendix/g-app-intents.html">
            <span class="en">Intents List: Google Apps</span>
          </a></li>
      <li><a href="<?cs var:toroot ?>guide/appendix/glossary.html">
            <span class="en">Glossary</span>
          </a></li>
    </ul>
  </li>

</ul>

<script type="text/javascript">
<!--
    buildToggleLists();
    changeNavLang(getLangPref());
//-->
</script>
