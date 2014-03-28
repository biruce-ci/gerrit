// Copyright (C) 2013 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.gerrit.server.account;

import static com.google.gerrit.server.account.GetPreferences.KEY_ID;
import static com.google.gerrit.server.account.GetPreferences.KEY_TARGET;
import static com.google.gerrit.server.account.GetPreferences.KEY_URL;
import static com.google.gerrit.server.account.GetPreferences.MY;
import static com.google.gerrit.server.account.GetPreferences.PREFERENCES;

import com.google.common.base.Strings;
import com.google.gerrit.extensions.restapi.AuthException;
import com.google.gerrit.extensions.restapi.ResourceNotFoundException;
import com.google.gerrit.extensions.restapi.RestModifyView;
import com.google.gerrit.extensions.webui.TopMenu;
import com.google.gerrit.reviewdb.client.Account;
import com.google.gerrit.reviewdb.client.AccountGeneralPreferences;
import com.google.gerrit.reviewdb.client.AccountGeneralPreferences.ChangeScreen;
import com.google.gerrit.reviewdb.client.AccountGeneralPreferences.CommentVisibilityStrategy;
import com.google.gerrit.reviewdb.client.AccountGeneralPreferences.DateFormat;
import com.google.gerrit.reviewdb.client.AccountGeneralPreferences.DiffView;
import com.google.gerrit.reviewdb.client.AccountGeneralPreferences.DownloadCommand;
import com.google.gerrit.reviewdb.client.AccountGeneralPreferences.DownloadScheme;
import com.google.gerrit.reviewdb.client.AccountGeneralPreferences.TimeFormat;
import com.google.gerrit.reviewdb.client.RefNames;
import com.google.gerrit.reviewdb.server.ReviewDb;
import com.google.gerrit.server.CurrentUser;
import com.google.gerrit.server.account.SetPreferences.Input;
import com.google.gerrit.server.git.MetaDataUpdate;
import com.google.gerrit.server.git.ProjectLevelConfig;
import com.google.gerrit.server.project.ProjectCache;
import com.google.gerrit.server.project.ProjectState;
import com.google.gwtorm.server.OrmException;
import com.google.inject.Inject;
import com.google.inject.Provider;

import org.eclipse.jgit.lib.Config;

import java.io.IOException;
import java.util.Collections;
import java.util.List;

public class SetPreferences implements RestModifyView<AccountResource, Input> {
  static class Input {
    Short changesPerPage;
    Boolean showSiteHeader;
    Boolean useFlashClipboard;
    DownloadScheme downloadScheme;
    DownloadCommand downloadCommand;
    Boolean copySelfOnEmail;
    DateFormat dateFormat;
    TimeFormat timeFormat;
    Boolean reversePatchSetOrder;
    Boolean showUsernameInReviewCategory;
    Boolean relativeDateInChangeTable;
    Boolean sizeBarInChangeTable;
    CommentVisibilityStrategy commentVisibilityStrategy;
    DiffView diffView;
    ChangeScreen changeScreen;
    List<TopMenu.MenuItem> my;
  }

  private final Provider<CurrentUser> self;
  private final AccountCache cache;
  private final ReviewDb db;
  private final MetaDataUpdate.User metaDataUpdateFactory;
  private final ProjectState allUsers;

  @Inject
  SetPreferences(Provider<CurrentUser> self, AccountCache cache, ReviewDb db,
      MetaDataUpdate.User metaDataUpdateFactory, ProjectCache projectCache) {
    this.self = self;
    this.cache = cache;
    this.db = db;
    this.metaDataUpdateFactory = metaDataUpdateFactory;
    this.allUsers = projectCache.getAllUsers();
  }

  @Override
  public GetPreferences.PreferenceInfo apply(AccountResource rsrc, Input i)
      throws AuthException, ResourceNotFoundException, OrmException,
      IOException {
    if (self.get() != rsrc.getUser()
        && !self.get().getCapabilities().canAdministrateServer()) {
      throw new AuthException("restricted to administrator");
    }
    if (i == null) {
      i = new Input();
    }

    Account.Id accountId = rsrc.getUser().getAccountId();
    AccountGeneralPreferences p;
    db.accounts().beginTransaction(accountId);
    try {
      Account a = db.accounts().get(accountId);
      if (a == null) {
        throw new ResourceNotFoundException();
      }

      p = a.getGeneralPreferences();
      if (p == null) {
        p = new AccountGeneralPreferences();
        a.setGeneralPreferences(p);
      }

      if (i.changesPerPage != null) {
        p.setMaximumPageSize(i.changesPerPage);
      }
      if (i.showSiteHeader != null) {
        p.setShowSiteHeader(i.showSiteHeader);
      }
      if (i.useFlashClipboard != null) {
        p.setUseFlashClipboard(i.useFlashClipboard);
      }
      if (i.downloadScheme != null) {
        p.setDownloadUrl(i.downloadScheme);
      }
      if (i.downloadCommand != null) {
        p.setDownloadCommand(i.downloadCommand);
      }
      if (i.copySelfOnEmail != null) {
        p.setCopySelfOnEmails(i.copySelfOnEmail);
      }
      if (i.dateFormat != null) {
        p.setDateFormat(i.dateFormat);
      }
      if (i.timeFormat != null) {
        p.setTimeFormat(i.timeFormat);
      }
      if (i.reversePatchSetOrder != null) {
        p.setReversePatchSetOrder(i.reversePatchSetOrder);
      }
      if (i.showUsernameInReviewCategory != null) {
        p.setShowUsernameInReviewCategory(i.showUsernameInReviewCategory);
      }
      if (i.relativeDateInChangeTable != null) {
        p.setRelativeDateInChangeTable(i.relativeDateInChangeTable);
      }
      if (i.sizeBarInChangeTable != null) {
        p.setSizeBarInChangeTable(i.sizeBarInChangeTable);
      }
      if (i.commentVisibilityStrategy != null) {
        p.setCommentVisibilityStrategy(i.commentVisibilityStrategy);
      }
      if (i.diffView != null) {
        p.setDiffView(i.diffView);
      }
      if (i.changeScreen != null) {
        p.setChangeScreen(i.changeScreen);
      }

      db.accounts().update(Collections.singleton(a));
      db.commit();
      storeMyMenus(accountId, i.my);
      cache.evict(accountId);
    } finally {
      db.rollback();
    }
    return new GetPreferences.PreferenceInfo(p, accountId, allUsers);
  }

  private void storeMyMenus(Account.Id accountId, List<TopMenu.MenuItem> my)
      throws IOException {
    ProjectLevelConfig prefsCfg =
        allUsers.getConfig(PREFERENCES,
            RefNames.refsUsers(accountId));
    Config cfg = prefsCfg.get();
    if (my != null) {
      unsetSection(cfg, MY);
      for (TopMenu.MenuItem item : my) {
        set(cfg, item.name, KEY_URL, item.url);
        set(cfg, item.name, KEY_TARGET, item.target);
        set(cfg, item.name, KEY_ID, item.id);
      }
    }
    MetaDataUpdate md =
        metaDataUpdateFactory.create(allUsers.getProject().getNameKey());
    md.setMessage("Updated preferences\n");
    prefsCfg.commit(md);
  }

  private static void set(Config cfg, String section, String key, String val) {
    if (Strings.isNullOrEmpty(val)) {
      cfg.unset(MY, section, key);
    } else {
      cfg.setString(MY, section, key, val);
    }
  }

  private static void unsetSection(Config cfg, String section) {
    cfg.unsetSection(section, null);
    for (String subsection: cfg.getSubsections(section)) {
      cfg.unsetSection(section, subsection);
    }
  }
}
