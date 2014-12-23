/*
 * Copyright 2000-2014 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.intellij.codeInsight.actions;

import com.intellij.codeInsight.CodeInsightBundle;
import com.intellij.codeInsight.hint.HintManager;
import com.intellij.codeInsight.hint.HintManagerImpl;
import com.intellij.codeInsight.hint.HintUtil;
import com.intellij.find.impl.FindInProjectUtil;
import com.intellij.formatting.FormattingModelBuilder;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.lang.LanguageFormatting;
import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.SelectionModel;
import com.intellij.openapi.keymap.KeymapUtil;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.vcs.changes.ChangeListManagerImpl;
import com.intellij.openapi.vfs.ReadonlyStatusHandler;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.*;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.LocalSearchScope;
import com.intellij.psi.search.SearchScope;
import com.intellij.psi.util.PsiUtilCore;
import com.intellij.ui.LightweightHint;
import com.intellij.util.diff.FilesTooBigForDiffException;
import com.intellij.vcsUtil.VcsUtil;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.TestOnly;

import javax.swing.*;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

public class ReformatCodeAction extends AnAction implements DumbAware {
  private static final Logger LOG = Logger.getInstance("#com.intellij.codeInsight.actions.ReformatCodeAction");

  private static final @NonNls String HELP_ID = "editing.codeReformatting";
  protected static ReformatFilesOptions myTestOptions;


  @Override
  public void actionPerformed(AnActionEvent event) {
    DataContext dataContext = event.getDataContext();
    final Project project = CommonDataKeys.PROJECT.getData(dataContext);
    if (project == null) {
      return;
    }
    PsiDocumentManager.getInstance(project).commitAllDocuments();
    final Editor editor = CommonDataKeys.EDITOR.getData(dataContext);
    final VirtualFile[] files = CommonDataKeys.VIRTUAL_FILE_ARRAY.getData(dataContext);

    PsiFile file = null;
    final PsiDirectory dir;
    boolean hasSelection = false;

    if (editor != null){
      file = PsiDocumentManager.getInstance(project).getPsiFile(editor.getDocument());
      if (file == null) return;
      dir = file.getContainingDirectory();
      hasSelection = editor.getSelectionModel().hasSelection();
    }
    else if (areFiles(files)) {
      final ReadonlyStatusHandler.OperationStatus operationStatus = ReadonlyStatusHandler.getInstance(project).ensureFilesWritable(files);
      if (!operationStatus.hasReadonlyFiles()) {
        ReformatFilesOptions selectedFlags = getReformatFilesOptions(project, files);
        if (selectedFlags == null)
          return;

        final boolean processOnlyChangedText = selectedFlags.getTextRangeType() == TextRangeType.VCS_CHANGED_TEXT;
        final boolean shouldOptimizeImports = selectedFlags.isOptimizeImports() && !DumbService.getInstance(project).isDumb();

        AbstractLayoutCodeProcessor processor = new ReformatCodeProcessor(project, convertToPsiFiles(files, project), null, processOnlyChangedText);
        if (shouldOptimizeImports) {
          processor = new OptimizeImportsProcessor(processor);
        }
        if (selectedFlags.isRearrangeCode()) {
          processor = new RearrangeCodeProcessor(processor);
        }

        processor.run();
      }
      return;
    }
    else {
      Project projectContext = PlatformDataKeys.PROJECT_CONTEXT.getData(dataContext);
      Module moduleContext = LangDataKeys.MODULE_CONTEXT.getData(dataContext);

      if (projectContext != null || moduleContext != null) {
        ReformatFilesOptions selectedFlags = getLayoutProjectOptions(project, moduleContext); // module menu - only 2 options available
        if (selectedFlags != null) {
          reformatModule(project, moduleContext, selectedFlags);
        }
        return;
      }

      PsiElement element = CommonDataKeys.PSI_ELEMENT.getData(dataContext);
      if (element == null) return;
      if (element instanceof PsiDirectoryContainer) {
        dir = ((PsiDirectoryContainer)element).getDirectories()[0];
      }
      else if (element instanceof PsiDirectory) {
        dir = (PsiDirectory)element;
      }
      else {
        file = element.getContainingFile();
        if (file == null) return;
        dir = file.getContainingDirectory();
      }
    }

    LayoutCodeOptions lastRunOptions = getLastRunReformatCodeOptions();
    TextRangeType processingType = lastRunOptions.getTextRangeType();
    final boolean optimizeImports = lastRunOptions.isOptimizeImports();
    final boolean rearrangeEntries;
    if (file != null && lastRunOptions instanceof LastRunReformatCodeOptionsProvider) {
      LastRunReformatCodeOptionsProvider options = (LastRunReformatCodeOptionsProvider)lastRunOptions;
      rearrangeEntries = options.isRearrangeCode(file.getLanguage());
    }
    else {
      rearrangeEntries = lastRunOptions.isRearrangeCode();
    }

    if (file == null && dir != null) {
      DirectoryFormattingOptions options = getDirectoryFormattingOptions(project, dir);
      if (options != null) {
        reformatDirectory(project, dir, options);
      }
      return;
    }

    //todo let when editor == null proceed and perform
    if (file == null || editor == null) return;

    TextRange range = null;
    if (hasSelection) {
      processingType = TextRangeType.SELECTED_TEXT;
      SelectionModel model = editor.getSelectionModel();
      range = TextRange.create(model.getSelectionStart(), model.getSelectionEnd());
    }
    else if (processingType == TextRangeType.VCS_CHANGED_TEXT) {
      if (isChangeNotTrackedForFile(project, file)) {
        processingType = TextRangeType.WHOLE_FILE;
      }
    }
    else {
      processingType = TextRangeType.WHOLE_FILE;
    }

    boolean processChangedTextOnly = processingType == TextRangeType.VCS_CHANGED_TEXT;

    AbstractLayoutCodeProcessor processor;
    if (optimizeImports && range == null) {
      processor = new OptimizeImportsProcessor(project, file);
      processor = new ReformatCodeProcessor(processor, processChangedTextOnly);
    }
    else {
      processor = new ReformatCodeProcessor(project, file, range, processChangedTextOnly);
    }

    if (rearrangeEntries) {
      processor = new RearrangeCodeProcessor(processor);
    }

    final Document document = PsiDocumentManager.getInstance(project).getDocument(file);
    final CharSequence charSeqBefore = document != null ? document.getImmutableCharSequence() : null;

    final PsiFile finalFile = file;
    final TextRangeType finalTextRangeType = processingType;

    //todo show it if available options =)

    processor.setPostRunnable(new Runnable() {
      @Override
      public void run() {
        if (document != null) {
          int totalLinesProcessed = getProcessedLinesNumber(finalFile, charSeqBefore);
          String info = prepareMessage(totalLinesProcessed, optimizeImports, rearrangeEntries, finalTextRangeType);
          showHint(editor, info);
        }
      }
    });

    processor.run();
  }


  private void showHint(@NotNull Editor editor, @NotNull String info) {
    JComponent component = HintUtil.createInformationLabel(info);
    LightweightHint hint = new LightweightHint(component);
    HintManagerImpl.getInstanceImpl().showEditorHint(hint, editor, HintManager.UNDER,
                                                     HintManager.HIDE_BY_ANY_KEY|
                                                     HintManager.HIDE_BY_TEXT_CHANGE|
                                                     HintManager.HIDE_BY_SCROLLING,
                                                     0, false);
  }

  @NotNull
  private String prepareMessage(int totalLinesProcessed, boolean optimizeImports, boolean rearrangeCode, @NotNull TextRangeType textRangeType) {
    String linesInfo = "";
    if (totalLinesProcessed == 0) {
      linesInfo = "No changes needed";
    }
    else if (totalLinesProcessed > 0) {
      linesInfo = "Changed " + totalLinesProcessed + " lines";
    }

    String scopeInfo = textRangeType == TextRangeType.VCS_CHANGED_TEXT ? ", processed only changed lines since last revision" : null;
    String actionsInfo = "Performed: formatting";
    if (optimizeImports) {
      actionsInfo += ", import optimization";
    }
    if (rearrangeCode) {
      actionsInfo += " and code rearrangement";
    }

    String shortcutInfo = "Show reformat dialog: " + KeymapUtil.getFirstKeyboardShortcutText(
      ActionManager.getInstance().getAction("ReformatFile"));

    String info = linesInfo;
    if (scopeInfo != null) {
      info += scopeInfo;
    }
    info += "\n";
    info += actionsInfo + "\n";
    info += shortcutInfo;

    return info;
  }


  private int getProcessedLinesNumber(final PsiFile file, final CharSequence before) {
    Project project = file.getProject();
    Document document = PsiDocumentManager.getInstance(project).getDocument(file);
    if (document == null) {
      return 0;
    }

    int totalLinesProcessed = 0;
    try {
      List<TextRange> ranges = FormatChangedTextUtil.calculateChangedTextRanges(project, file, before);
      for (TextRange range : ranges) {
        int lineStartNumber = document.getLineNumber(range.getStartOffset());
        int lineEndNumber = document.getLineNumber(range.getEndOffset());

        int linesCoveredByRange = lineEndNumber - lineStartNumber + 1;
        totalLinesProcessed += linesCoveredByRange;
      }
    }
    catch (FilesTooBigForDiffException e) {
      e.printStackTrace();
    }
    return totalLinesProcessed;
  }


  private static boolean isChangeNotTrackedForFile(@NotNull Project project, @NotNull PsiFile file) {
    boolean isUnderVcs = VcsUtil.isFileUnderVcs(project, VcsUtil.getFilePath(file.getVirtualFile()));
    if (!isUnderVcs) return true;

    ChangeListManagerImpl changeListManager = ChangeListManagerImpl.getInstanceImpl(project);
    List<VirtualFile> unversionedFiles = changeListManager.getUnversionedFiles();
    if (unversionedFiles.contains(file.getVirtualFile())) {
      return true;
    }

    return false;
  }

  @Nullable
  private static DirectoryFormattingOptions getDirectoryFormattingOptions(@NotNull Project project, @NotNull PsiDirectory dir) {
    LayoutDirectoryDialog dialog = new LayoutDirectoryDialog(
      project,
      CodeInsightBundle.message("process.reformat.code"),
      CodeInsightBundle.message("process.scope.directory", dir.getVirtualFile().getPath()),
      FormatChangedTextUtil.hasChanges(dir)
    );

    boolean enableIncludeDirectoriesCb = dir.getSubdirectories().length > 0;
    dialog.setEnabledIncludeSubdirsCb(enableIncludeDirectoriesCb);
    dialog.setSelectedIncludeSubdirsCb(enableIncludeDirectoriesCb);

    if (dialog.showAndGet()) {
      return dialog;
    }
    return null;
  }

  private static void reformatDirectory(@NotNull Project project,
                                        @NotNull PsiDirectory dir,
                                        @NotNull DirectoryFormattingOptions options)
  {
    AbstractLayoutCodeProcessor processor = new ReformatCodeProcessor(
      project, dir, options.isIncludeSubdirectories(), options.getTextRangeType() == TextRangeType.VCS_CHANGED_TEXT
    );

    registerScopeFilter(processor, options.getSearchScope());
    registerFileMaskFilter(processor, options.getFileTypeMask());

    if (options.isOptimizeImports()) {
      processor = new OptimizeImportsProcessor(processor);
    }
    if (options.isRearrangeCode()) {
      processor = new RearrangeCodeProcessor(processor);
    }

    processor.run();
  }

  private static void reformatModule(@NotNull Project project,
                                     @Nullable Module moduleContext,
                                     @NotNull ReformatFilesOptions selectedFlags)
  {
    boolean shouldOptimizeImports = selectedFlags.isOptimizeImports() && !DumbService.getInstance(project).isDumb();
    boolean processOnlyChangedText = selectedFlags.getTextRangeType() == TextRangeType.VCS_CHANGED_TEXT;

    AbstractLayoutCodeProcessor processor;
    if (moduleContext != null)
      processor = new ReformatCodeProcessor(project, moduleContext, processOnlyChangedText);
    else
      processor = new ReformatCodeProcessor(project, processOnlyChangedText);

    registerScopeFilter(processor, selectedFlags.getSearchScope());
    registerFileMaskFilter(processor, selectedFlags.getFileTypeMask());

    if (shouldOptimizeImports) {
      processor = new OptimizeImportsProcessor(processor);
    }

    if (selectedFlags.isRearrangeCode()) {
      processor = new RearrangeCodeProcessor(processor);
    }

    processor.run();
  }

  public static void registerScopeFilter(@NotNull AbstractLayoutCodeProcessor processor, @Nullable final SearchScope scope) {
    if (scope == null) {
      return;
    }

    processor.addFileFilter(new FileFilter() {
      @Override
      public boolean accept(@NotNull VirtualFile file) {
        if (scope instanceof LocalSearchScope) {
          return ((LocalSearchScope)scope).isInScope(file);
        }
        if (scope instanceof GlobalSearchScope) {
          return ((GlobalSearchScope)scope).contains(file);
        }

        return false;
      }
    });
  }

  public static void registerFileMaskFilter(@NotNull AbstractLayoutCodeProcessor processor, @Nullable String fileTypeMask) {
    if (fileTypeMask == null)
      return;

    final Pattern pattern = getFileTypeMaskPattern(fileTypeMask);
    if (pattern != null) {
      processor.addFileFilter(new FileFilter() {
        @Override
        public boolean accept(@NotNull VirtualFile file) {
          return pattern.matcher(file.getName()).matches();
        }
      });
    }
  }

  @Nullable
  private static Pattern getFileTypeMaskPattern(@Nullable String mask) {
    try {
      return FindInProjectUtil.createFileMaskRegExp(mask);
    } catch (PatternSyntaxException e) {
      LOG.info("Error while processing file mask: ", e);
      return null;
    }
  }

  public static PsiFile[] convertToPsiFiles(final VirtualFile[] files,Project project) {
    final PsiManager manager = PsiManager.getInstance(project);
    final ArrayList<PsiFile> result = new ArrayList<PsiFile>();
    for (VirtualFile virtualFile : files) {
      final PsiFile psiFile = manager.findFile(virtualFile);
      if (psiFile != null) result.add(psiFile);
    }
    return PsiUtilCore.toPsiFileArray(result);
  }

  @Override
  public void update(AnActionEvent event){
    Presentation presentation = event.getPresentation();
    DataContext dataContext = event.getDataContext();
    Project project = CommonDataKeys.PROJECT.getData(dataContext);
    if (project == null){
      presentation.setEnabled(false);
      return;
    }

    Editor editor = CommonDataKeys.EDITOR.getData(dataContext);

    final VirtualFile[] files = CommonDataKeys.VIRTUAL_FILE_ARRAY.getData(dataContext);

    if (editor != null){
      PsiFile file = PsiDocumentManager.getInstance(project).getPsiFile(editor.getDocument());
      if (file == null || file.getVirtualFile() == null) {
        presentation.setEnabled(false);
        return;
      }

      if (LanguageFormatting.INSTANCE.forContext(file)  != null) {
        presentation.setEnabled(true);
        return;
      }
    }
    else if (files!= null && areFiles(files)) {
      boolean anyFormatters = false;
      for (VirtualFile virtualFile : files) {
        if (virtualFile.isDirectory()) {
          presentation.setEnabled(false);
          return;
        }
        final PsiFile psiFile = PsiManager.getInstance(project).findFile(virtualFile);
        if (psiFile == null) {
          presentation.setEnabled(false);
          return;
        }
        final FormattingModelBuilder builder = LanguageFormatting.INSTANCE.forContext(psiFile);
        if (builder != null) {
          anyFormatters = true;
        }
      }
      if (!anyFormatters) {
        presentation.setEnabled(false);
        return;
      }
    }
    else if (files != null && files.length == 1) {
      // skip. Both directories and single files are supported.
    }
    else if (LangDataKeys.MODULE_CONTEXT.getData(dataContext) == null &&
             PlatformDataKeys.PROJECT_CONTEXT.getData(dataContext) == null) {
      PsiElement element = CommonDataKeys.PSI_ELEMENT.getData(dataContext);
      if (element == null) {
        presentation.setEnabled(false);
        return;
      }
      if (!(element instanceof PsiDirectory)) {
        PsiFile file = element.getContainingFile();
        if (file == null || LanguageFormatting.INSTANCE.forContext(file) == null) {
          presentation.setEnabled(false);
          return;
        }
      }
    }
    presentation.setEnabled(true);
  }

  @Nullable
  private static ReformatFilesOptions getReformatFilesOptions(@NotNull Project project, @NotNull VirtualFile[] files) {
    if (ApplicationManager.getApplication().isUnitTestMode()) {
      return myTestOptions;
    }
    ReformatFilesDialog dialog = new ReformatFilesDialog(project, files);
    if (!dialog.showAndGet()) {
      return null;
    }
    return dialog;
  }

  @Nullable
  private static ReformatFilesOptions getLayoutProjectOptions(@NotNull Project project, @Nullable Module module) {
    if (ApplicationManager.getApplication().isUnitTestMode()) {
      return myTestOptions;
    }

    final String text = module != null ? CodeInsightBundle.message("process.scope.module", module.getModuleFilePath())
                                       : CodeInsightBundle.message("process.scope.project", project.getPresentableUrl());

    final boolean enableOnlyVCSChangedRegions = module != null ? FormatChangedTextUtil.hasChanges(module)
                                                               : FormatChangedTextUtil.hasChanges(project);

    LayoutProjectCodeDialog dialog =
      new LayoutProjectCodeDialog(project, CodeInsightBundle.message("process.reformat.code"), text, enableOnlyVCSChangedRegions);
    if (!dialog.showAndGet()) {
      return null;
    }
    return dialog;
  }

  @TestOnly
  protected static void setTestOptions(ReformatFilesOptions options) {
    myTestOptions = options;
  }

  public static boolean areFiles(final VirtualFile[] files) {
    if (files == null) return false;
    if (files.length < 2) return false;
    for (VirtualFile virtualFile : files) {
      if (virtualFile.isDirectory()) return false;
    }
    return true;
  }

  public LayoutCodeOptions getLastRunReformatCodeOptions() {
    if (ApplicationManager.getApplication().isUnitTestMode()) {
      return myTestOptions;
    }
    return new LastRunReformatCodeOptionsProvider(PropertiesComponent.getInstance());
  }
}


