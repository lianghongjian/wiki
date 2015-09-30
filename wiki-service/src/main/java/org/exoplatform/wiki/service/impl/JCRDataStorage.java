package org.exoplatform.wiki.service.impl;

import org.apache.commons.lang.StringUtils;
import org.chromattic.api.ChromatticSession;
import org.chromattic.common.IO;
import org.chromattic.core.api.ChromatticSessionImpl;
import org.exoplatform.commons.utils.ObjectPageList;
import org.exoplatform.commons.utils.PageList;
import org.exoplatform.container.ExoContainerContext;
import org.exoplatform.container.configuration.ConfigurationManager;
import org.exoplatform.container.xml.ValuesParam;
import org.exoplatform.portal.config.UserACL;
import org.exoplatform.portal.config.UserPortalConfigService;
import org.exoplatform.portal.config.model.PortalConfig;
import org.exoplatform.services.jcr.access.AccessControlEntry;
import org.exoplatform.services.jcr.access.AccessControlList;
import org.exoplatform.services.jcr.impl.core.query.QueryImpl;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;
import org.exoplatform.services.security.ConversationState;
import org.exoplatform.services.security.Identity;
import org.exoplatform.services.security.IdentityConstants;
import org.exoplatform.wiki.chromattic.ext.ntdef.NTVersion;
import org.exoplatform.wiki.chromattic.ext.ntdef.VersionableMixin;
import org.exoplatform.wiki.mow.api.*;
import org.exoplatform.wiki.mow.api.Template;
import org.exoplatform.wiki.mow.core.api.MOWService;
import org.exoplatform.wiki.mow.core.api.WikiStoreImpl;
import org.exoplatform.wiki.mow.core.api.wiki.*;
import org.exoplatform.wiki.resolver.TitleResolver;
import org.exoplatform.wiki.service.*;
import org.exoplatform.wiki.service.search.SearchResult;
import org.exoplatform.wiki.service.search.TemplateSearchData;
import org.exoplatform.wiki.service.search.TemplateSearchResult;
import org.exoplatform.wiki.service.search.WikiSearchData;
import org.exoplatform.wiki.utils.Utils;
import org.exoplatform.wiki.utils.VersionNameComparatorDesc;

import javax.jcr.*;
import javax.jcr.query.Query;
import javax.jcr.query.QueryResult;
import javax.jcr.query.Row;
import javax.jcr.query.RowIterator;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.*;

public class JCRDataStorage implements DataStorage {
  private static final Log log = ExoLogger.getLogger(JCRDataStorage.class);

  private static final int MAX_EXCERPT_LENGTH = 430;

  private static final int CIRCULAR_RENAME_FLAG = 1000;

  private MOWService mowService;

  public JCRDataStorage(MOWService mowService) {
    this.mowService = mowService;
  }

  @Override
  // TODO check who has admin permission ?
  public Wiki getWikiByTypeAndOwner(String wikiType, String wikiOwner, boolean hasAdminPermission) throws Exception {
    WikiImpl wikiImpl = fetchWikiImpl(wikiType, wikiOwner, hasAdminPermission);
    return convertWikiImplToWiki(wikiImpl);
  }

  @Override
  public Wiki createWiki(String wikiType, String owner) throws Exception {
    Model model = getModel();
    WikiStoreImpl wStore = (WikiStoreImpl) model.getWikiStore();

    WikiContainer wikiContainer = wStore.getWikiContainer(WikiType.valueOf(wikiType.toUpperCase()));
    WikiImpl wikiImpl = wikiContainer.addWiki(owner);

    model.save();

    return convertWikiImplToWiki(wikiImpl);
  }

  /**
   * Create a wiki page with the given pageId, under the node of the parentPage node.
   * @param wiki
   * @param parentPage
   * @param page
   * @return
   * @throws Exception
   */
  @Override
  public Page createPage(Wiki wiki, Page parentPage, Page page) throws Exception {
    if (parentPage == null) {
      throw new IllegalArgumentException("Parent page cannot be null when creating the new page " + wiki.getType() + ":" + wiki.getOwner() + ":" + page.getName());
    }

    Model model = getModel();
    WikiImpl wikiImpl = fetchWikiImpl(wiki.getType(), wiki.getOwner(), true);
    PageImpl parentPageImpl = fetchPageImpl(parentPage.getWikiType(), parentPage.getWikiOwner(), parentPage.getName());
    PageImpl pageImpl = wikiImpl.createWikiPage();
    pageImpl.setName(page.getName());
    parentPageImpl.addWikiPage(pageImpl);
    ConversationState conversationState = ConversationState.getCurrent();
    String creator = null;
    if (conversationState != null && conversationState.getIdentity() != null) {
      creator = conversationState.getIdentity().getUserId();
    }
    pageImpl.setOwner(creator);
    setFullPermissionForOwner(pageImpl, creator);
    pageImpl.setTitle(page.getTitle());
    String text = "";
    if(page.getContent() != null) {
      text = page.getContent().getText();
    }
    pageImpl.getContent().setText(text);

    // create a first version
    pageImpl.makeVersionable();
    pageImpl.checkin();
    pageImpl.checkout();

    //update LinkRegistry
    LinkRegistry linkRegistry = wikiImpl.getLinkRegistry();
    String newEntryName = getLinkEntryName(wiki.getType(), wiki.getOwner(), page.getName());
    String newEntryAlias = getLinkEntryAlias(wiki.getType(), wiki.getOwner(), page.getName());
    LinkEntry newEntry = linkRegistry.getLinkEntries().get(newEntryName);
    if (newEntry == null) {
      newEntry = linkRegistry.createLinkEntry();
      linkRegistry.getLinkEntries().put(newEntryName, newEntry);
      newEntry.setAlias(newEntryAlias);
      newEntry.setTitle(page.getTitle());
    }
    //This line must be outside if statement to break chaining list when add new page with name that was used in list.
    newEntry.setNewLink(newEntry);

    model.save();

    return convertPageImplToPage(pageImpl);
  }

  @Override
  public Page getPageOfWikiByName(String wikiType, String wikiOwner, String pageName) throws Exception {
    PageImpl pageImpl = null;

    WikiImpl wiki = fetchWikiImpl(wikiType, wikiOwner, true);

    if(wiki != null) {
      if (WikiNodeType.Definition.WIKI_HOME_NAME.equals(pageName) || pageName == null) {
        pageImpl = wiki.getWikiHome();
      } else {
        pageImpl = fetchPageImpl(wikiType, wikiOwner, pageName);
        if (pageImpl == null && (pageImpl = wiki.getWikiHome()) != null) {
          String wikiHomeId = TitleResolver.getId(pageImpl.getTitle(), true);
          if (!wikiHomeId.equals(pageName)) {
            pageImpl = null;
          }
        }
      }
    }

    Page page = null;
    if(pageImpl != null) {
      page = convertPageImplToPage(pageImpl);
      page.setWikiId(wiki.getName());
      page.setWikiType(wiki.getType());
      page.setWikiOwner(wiki.getOwner());
    }

    return page;
  }

  @Override
  public Page getPageById(String id) throws Exception {
    Model model = getModel();
    WikiStoreImpl wStore = (WikiStoreImpl) model.getWikiStore();
    ChromatticSession session = wStore.getSession();

    return convertPageImplToPage(session.findById(PageImpl.class, id));
  }

  @Override
  public Page getParentPageOf(Page page) throws Exception {
    PageImpl pageImpl = fetchPageImpl(page.getWikiType(), page.getWikiOwner(), page.getName());
    PageImpl parentPageImpl = pageImpl.getParentPage();

    Page parentPage = convertPageImplToPage(parentPageImpl);
    if(parentPage != null) {
      parentPage.setWikiId(page.getWikiId());
      parentPage.setWikiType(page.getWikiType());
      parentPage.setWikiOwner(page.getWikiOwner());
    }

    return parentPage;
  }

  @Override
  public List<Page> getChildrenPageOf(Page page) throws Exception {
    List<Page> childrenPages = new ArrayList<>();

    PageImpl pageImpl = fetchPageImpl(page.getWikiType(), page.getWikiOwner(), page.getName());
    if(pageImpl == null) {
      throw new Exception("Page " + page.getWikiType() + ":" + page.getWikiOwner() + ":" + page.getName() + " does not exist, cannot get its children.");
    }

    Map<String, PageImpl> childrenPageImpls = pageImpl.getChildPages();
    for(PageImpl childPageImpl : childrenPageImpls.values()) {
      Page childPage = convertPageImplToPage(childPageImpl);
      childPage.setWikiType(page.getWikiType());
      childPage.setWikiOwner(page.getWikiOwner());
      childrenPages.add(childPage);
    }

    return childrenPages;
  }

  @Override
  public void createTemplatePage(ConfigurationManager configurationManager, String templateSourcePath, String targetPath) {
    Model model = getModel();
    WikiStoreImpl wStore = (WikiStoreImpl) model.getWikiStore();
    ChromatticSession session = wStore.getSession();
    if (templateSourcePath != null) {
      InputStream is = null;
      try {
        is = configurationManager.getInputStream(templateSourcePath);
        int type = ImportUUIDBehavior.IMPORT_UUID_CREATE_NEW;
        if(((Node)session.getJCRSession().getItem(targetPath)).hasNode(WikiNodeType.WIKI_TEMPLATE_CONTAINER)) {
          type = ImportUUIDBehavior.IMPORT_UUID_COLLISION_REPLACE_EXISTING;
        }
        session.getJCRSession().importXML(targetPath, is, type);
        session.save();
      } catch(Exception e) {
        // TODO
        e.printStackTrace();
      } finally {
        if (is != null) {
          try {
            is.close();
          } catch (IOException e) {
            // TODO
            e.printStackTrace();
          }
        }
      }
    }
  }

  @Override
  public void createTemplatePage(String title, WikiPageParams params) throws Exception {
    Model model = getModel();
    TemplateContainer templatesContainer = getTemplatesContainer(params.getType(), params.getOwner());
    ConversationState conversationState = ConversationState.getCurrent();
    try {
      TemplateImpl template = templatesContainer.createTemplatePage();
      String pageId = TitleResolver.getId(title, false);
      template.setName(pageId);
      templatesContainer.addPage(template.getName(), template);
      String creator = null;
      if (conversationState != null && conversationState.getIdentity() != null) {
        creator = conversationState.getIdentity().getUserId();
      }
      template.setOwner(creator);
      template.setTitle(title);
      template.getContent().setText("");
      model.save();
    } catch (Exception e) {
      log.error("Can not create Template page", e);
    }
  }

  public void deleteTemplatePage(String wikiType, String wikiOwner, String templateName) throws Exception {
    Model model = getModel();
    TemplateContainer templatesContainer = getTemplatesContainer(wikiType, wikiOwner);
    TemplateImpl templateImpl = templatesContainer.getTemplate(templateName);
    templateImpl.remove();
    model.save();
  }

  @Override
  public void deletePage(String wikiType, String wikiOwner, String pageId) throws Exception {
    Model model = getModel();
    WikiStoreImpl wStore = (WikiStoreImpl) model.getWikiStore();
    PageImpl page = fetchPageImpl(wikiType, wikiOwner, pageId);
    if(page == null) {
      throw new Exception("Page " + wikiType + ":" + wikiOwner + ":" + pageId + " does not exist, cannot delete it.");
    }

    ChromatticSession session = wStore.getSession();
    RemovedMixin mix = session.create(RemovedMixin.class);
    session.setEmbedded(page, RemovedMixin.class, mix);
    mix.setRemovedBy(Utils.getCurrentUser());
    Calendar calendar = GregorianCalendar.getInstance();
    calendar.setTimeInMillis(new Date().getTime());
    mix.setRemovedDate(calendar.getTime());
    mix.setParentPath(page.getParentPage().getPath());
    WikiImpl wiki = fetchWikiImpl(wikiType, wikiOwner, false);
    Trash trash = wiki.getTrash();
    if (trash.isHasPage(page.getName())) {
      PageImpl oldDeleted = trash.getPage(page.getName());
      String removedDate = oldDeleted.getRemovedMixin().getRemovedDate().toGMTString();
      String newName = page.getName() + "_" + removedDate.replaceAll(" ", "-").replaceAll(":", "-");
      trash.addChild(newName, oldDeleted);
    }
    trash.addRemovedWikiPage(page);

    //update LinkRegistry
    LinkRegistry linkRegistry = wiki.getLinkRegistry();
    if (linkRegistry.getLinkEntries().get(getLinkEntryName(wikiType, wikiOwner, pageId)) != null) {
      linkRegistry.getLinkEntries().get(getLinkEntryName(wikiType, wikiOwner, pageId)).setNewLink(null);
    }

    session.save();
  }

  @Override
  public Template getTemplatePage(WikiPageParams params, String templateId) throws Exception {
    return convertTemplateImplToTemplate(getTemplatesContainer(params.getType(), params.getOwner()).getTemplate(templateId));
  }

  @Override
  public Map<String, Template> getTemplates(WikiPageParams params) throws Exception {
    Map<String, Template> templates = new HashMap<>();
    Map<String, TemplateImpl> templatesImpl = getTemplatesContainer(params.getType(), params.getOwner()).getTemplates();
    for(String templateImplKey : templatesImpl.keySet()) {
      templates.put(templateImplKey, convertTemplateImplToTemplate(templatesImpl.get(templateImplKey)));
    }
    return templates;
  }

  private TemplateContainer getTemplatesContainer(String wikiType, String wikiOwner) throws Exception {
    WikiImpl wiki = fetchWikiImpl(wikiType, wikiOwner, true);
    return wiki.getPreferences().getTemplateContainer();
  }

  @Override
  public void deleteDraftOfPage(Page page, String username) throws Exception {
    Model model = getModel();
    WikiStoreImpl wStore = (WikiStoreImpl) model.getWikiStore();
    UserWiki userWiki = (UserWiki) wStore.getWiki(WikiType.USER, username);
    PageImpl draftPagesContainer = userWiki.getDraftPagesContainer();
    Map<String, PageImpl> childPages = draftPagesContainer.getChildPages();
    for(PageImpl childPage : childPages.values()) {
      String targetPageId = ((DraftPageImpl) childPage).getTargetPage();
      if(targetPageId != null && targetPageId.equals(page.getId())) {
        childPage.remove();
        return;
      }
    }

    if(log.isDebugEnabled()) {
      log.debug("No draft page of page " + page.getWikiType() + ":" + page.getWikiOwner()
              + ":" + page.getName() + " for user " + username + ", so nothing to delete.");
    }
  }

  @Override
  public void deleteDraftById(String newDraftPageId, String username) throws Exception {
    Model model = getModel();
    WikiStoreImpl wStore = (WikiStoreImpl) model.getWikiStore();
    UserWiki userWiki = (UserWiki) wStore.getWiki(WikiType.USER, username);
    PageImpl draftPagesContainer = userWiki.getDraftPagesContainer();
    Map<String, PageImpl> childPages = draftPagesContainer.getChildPages();
    for(PageImpl childPage : childPages.values()) {
      if(childPage.getName().equals(newDraftPageId)) {
        childPage.remove();
        return;
      }
    }

    throw new Exception("Cannot delete draft page of " + newDraftPageId + " of user " + username + " because it does not exist.");
  }

  @Override
  public void renamePage(String wikiType, String wikiOwner, String pageName, String newName, String newTitle) throws Exception {
    PageImpl currentPage = fetchPageImpl(wikiType, wikiOwner, pageName);
    PageImpl parentPage = currentPage.getParentPage();
    RenamedMixin mix = currentPage.getRenamedMixin();
    if (mix == null) {
      mix = parentPage.getChromatticSession().create(RenamedMixin.class);
      currentPage.setRenamedMixin(mix);
      List<String> ids = new ArrayList<>();
      ids.add(pageName);
      mix.setOldPageIds(ids.toArray(new String[]{}));
    }
    List<String> ids = new ArrayList<>();
    for (String id : mix.getOldPageIds()) {
      ids.add(id);
    }
    mix.setOldPageIds(ids.toArray(new String[]{}));
    currentPage.setName(newName);
    getModel().save();
    currentPage.setTitle(newTitle);
    getModel().save();

    //update LinkRegistry
    WikiImpl wiki = (WikiImpl) parentPage.getWiki();
    LinkRegistry linkRegistry = wiki.getLinkRegistry();
    String newEntryName = getLinkEntryName(wikiType, wikiOwner, newName);
    String newEntryAlias = getLinkEntryAlias(wikiType, wikiOwner, newName);
    LinkEntry newEntry = linkRegistry.getLinkEntries().get(newEntryName);
    LinkEntry entry = linkRegistry.getLinkEntries().get(getLinkEntryName(wikiType, wikiOwner, pageName));
    if (newEntry == null) {
      newEntry = linkRegistry.createLinkEntry();
      linkRegistry.getLinkEntries().put(newEntryName, newEntry);
      newEntry.setAlias(newEntryAlias);
      newEntry.setNewLink(newEntry);
      newEntry.setTitle(newTitle);
      if (entry != null) {
        entry.setNewLink(newEntry);
      }
    } else if (entry == null) {
      newEntry.setNewLink(newEntry);
    } else {
      processCircularRename(entry, newEntry);
    }
    parentPage.getChromatticSession().save();
  }

  @Override
  public void movePage(WikiPageParams currentLocationParams, WikiPageParams newLocationParams) throws Exception {
    PageImpl destPage = fetchPageImpl(newLocationParams.getType(),
            newLocationParams.getOwner(),
            newLocationParams.getPageId());
    if (destPage == null || !destPage.hasPermission(PermissionType.EDITPAGE)) {
      throw new Exception("Destination page " + newLocationParams.getType() + ":" +
              newLocationParams.getOwner() + ":" + newLocationParams.getPageId() + " does not exist");
    }
    Model model = getModel();
    WikiStoreImpl wStore = (WikiStoreImpl) model.getWikiStore();
    ChromatticSession session = wStore.getSession();
    PageImpl movePage = fetchPageImpl(currentLocationParams.getType(),
            currentLocationParams.getOwner(),
            currentLocationParams.getPageId());
    WikiImpl sourceWiki = (WikiImpl) movePage.getWiki();
    MovedMixin mix = movePage.getMovedMixin();
    if (mix == null) {
      movePage.setMovedMixin(session.create(MovedMixin.class));
      mix = movePage.getMovedMixin();
      mix.setTargetPage(movePage.getParentPage());
    }
    mix.setTargetPage(destPage);
    WikiImpl destWiki = (WikiImpl) destPage.getWiki();
    movePage.setParentPage(destPage);
    movePage.setMinorEdit(false);

    // Update permission if moving page to other space or other wiki
    Collection<AttachmentImpl> attachments = movePage.getAttachmentsExcludeContentByRootPermisison();
    HashMap<String, String[]> pagePermission = movePage.getPermission();
    if (PortalConfig.GROUP_TYPE.equals(currentLocationParams.getType())
            && (!currentLocationParams.getOwner().equals(newLocationParams.getOwner())
            || !PortalConfig.GROUP_TYPE.equals(newLocationParams.getType()))) {
      // Remove old space permission first
      Iterator<Map.Entry<String, String[]>> pagePermissionIterator = pagePermission.entrySet().iterator();
      while (pagePermissionIterator.hasNext()) {
        Map.Entry<String, String[]> permissionEntry = pagePermissionIterator.next();
        if (StringUtils.substringAfter(permissionEntry.getKey(), ":").equals(currentLocationParams.getOwner())) {
          pagePermissionIterator.remove();
        }
      }
      for (AttachmentImpl attachment : attachments) {
        HashMap<String, String[]> attachmentPermission = attachment.getPermission();
        Iterator<Map.Entry<String, String[]>> attachmentPermissionIterator = attachmentPermission.entrySet().iterator();
        while (attachmentPermissionIterator.hasNext()) {
          Map.Entry<String, String[]> permissionEntry = attachmentPermissionIterator.next();
          if (StringUtils.substringAfter(permissionEntry.getKey(), ":").equals(currentLocationParams.getOwner())) {
            attachmentPermissionIterator.remove();
          }
        }
        attachment.setPermission(attachmentPermission);
      }
    }

    // Update permission by inherit from parent
    HashMap<String, String[]> parentPermissions = destPage.getPermission();
    pagePermission.putAll(parentPermissions);

    // Set permission to page
    movePage.setPermission(pagePermission);

    for (AttachmentImpl attachment : attachments) {
      HashMap<String, String[]> attachmentPermission = attachment.getPermission();
      attachmentPermission.putAll(parentPermissions);
      attachment.setPermission(attachmentPermission);
    }


    //update LinkRegistry
    if (!newLocationParams.getType().equals(currentLocationParams.getType())
            || (PortalConfig.GROUP_TYPE.equals(currentLocationParams.getType())
            && !currentLocationParams.getOwner().equals(newLocationParams.getOwner()))) {
      LinkRegistry sourceLinkRegistry = sourceWiki.getLinkRegistry();
      LinkRegistry destLinkRegistry = destWiki.getLinkRegistry();
      String newEntryName = getLinkEntryName(newLocationParams.getType(),
              newLocationParams.getOwner(),
              currentLocationParams.getPageId());
      String newEntryAlias = getLinkEntryAlias(newLocationParams.getType(),
              newLocationParams.getOwner(),
              currentLocationParams.getPageId());
      LinkEntry newEntry = destLinkRegistry.getLinkEntries().get(newEntryName);
      LinkEntry entry =
              sourceLinkRegistry.getLinkEntries().get(
                      getLinkEntryName(currentLocationParams.getType(),
                              currentLocationParams.getOwner(),
                              currentLocationParams.getPageId()));
      if (newEntry == null) {
        newEntry = destLinkRegistry.createLinkEntry();
        destLinkRegistry.getLinkEntries().put(newEntryName, newEntry);
        newEntry.setAlias(newEntryAlias);
        newEntry.setNewLink(newEntry);
        newEntry.setTitle(destPage.getTitle());
        if (entry != null) {
          entry.setNewLink(newEntry);
        }
      } else if (entry == null) {
        newEntry.setNewLink(newEntry);
      } else {
        processCircularRename(entry, newEntry);
      }
    }
    session.save();
  }

  @Override
  public List<PermissionEntry> getWikiPermission(String wikiType, String wikiOwner) throws Exception {
    List<PermissionEntry> permissionEntries = new ArrayList<>();

    Model model = getModel();
    Wiki wiki = getWikiWithoutPermission(wikiType, wikiOwner, model);
    if (wiki == null) {
      return permissionEntries;
    }
    if (!wiki.isDefaultPermissionsInited()) {
      List<String> permissions = getWikiDefaultPermissions(wikiType, wikiOwner);
      wiki.setPermissions(permissions);
      wiki.setDefaultPermissionsInited(true);
      HashMap<String, String[]> permMap = new HashMap<>();
      for (String perm : permissions) {
        String[] actions = perm.substring(0, perm.indexOf(":")).split(",");
        perm = perm.substring(perm.indexOf(":") + 1);
        String id = perm.substring(perm.indexOf(":") + 1);
        List<String> jcrActions = new ArrayList<>();
        for (String action : actions) {
          if (PermissionType.VIEWPAGE.toString().equals(action)) {
            jcrActions.add(org.exoplatform.services.jcr.access.PermissionType.READ);
          } else if (PermissionType.EDITPAGE.toString().equals(action)) {
            jcrActions.add(org.exoplatform.services.jcr.access.PermissionType.ADD_NODE);
            jcrActions.add(org.exoplatform.services.jcr.access.PermissionType.REMOVE);
            jcrActions.add(org.exoplatform.services.jcr.access.PermissionType.SET_PROPERTY);
          }
        }
        permMap.put(id, jcrActions.toArray(new String[jcrActions.size()]));
      }
      updateAllPagesPermissions(wikiType, wikiOwner, permMap);
    }
    List<String> permissions = wiki.getPermissions();
    for (String perm : permissions) {
      String[] actions = perm.substring(0, perm.indexOf(":")).split(",");
      perm = perm.substring(perm.indexOf(":") + 1);
      String idType = perm.substring(0, perm.indexOf(":"));
      String id = perm.substring(perm.indexOf(":") + 1);

      PermissionEntry entry = new PermissionEntry();
      if (IDType.USER.toString().equals(idType)) {
        entry.setIdType(IDType.USER);
      } else if (IDType.GROUP.toString().equals(idType)) {
        entry.setIdType(IDType.GROUP);
      } else if (IDType.MEMBERSHIP.toString().equals(idType)) {
        entry.setIdType(IDType.MEMBERSHIP);
      }
      entry.setId(id);
      org.exoplatform.wiki.service.Permission[] perms = new org.exoplatform.wiki.service.Permission[4];
      perms[0] = new org.exoplatform.wiki.service.Permission();
      perms[0].setPermissionType(PermissionType.VIEWPAGE);
      perms[1] = new org.exoplatform.wiki.service.Permission();
      perms[1].setPermissionType(PermissionType.EDITPAGE);
      perms[2] = new org.exoplatform.wiki.service.Permission();
      perms[2].setPermissionType(PermissionType.ADMINPAGE);
      perms[3] = new org.exoplatform.wiki.service.Permission();
      perms[3].setPermissionType(PermissionType.ADMINSPACE);
      for (String action : actions) {
        if (PermissionType.VIEWPAGE.toString().equals(action)) {
          perms[0].setAllowed(true);
        } else if (PermissionType.EDITPAGE.toString().equals(action)) {
          perms[1].setAllowed(true);
        } else if (PermissionType.ADMINPAGE.toString().equals(action)) {
          perms[2].setAllowed(true);
        } else if (PermissionType.ADMINSPACE.toString().equals(action)) {
          perms[3].setAllowed(true);
        }
      }
      entry.setPermissions(perms);

      permissionEntries.add(entry);
    }

    return permissionEntries;
  }

  @Override
  public List<String> getWikiDefaultPermissions(String wikiType, String wikiOwner) throws Exception {
    String view = new StringBuilder().append(PermissionType.VIEWPAGE).toString();
    String viewEdit = new StringBuilder().append(PermissionType.VIEWPAGE).append(",").append(PermissionType.EDITPAGE).toString();
    String all = new StringBuilder().append(PermissionType.VIEWPAGE)
            .append(",")
            .append(PermissionType.EDITPAGE)
            .append(",")
            .append(PermissionType.ADMINPAGE)
            .append(",")
            .append(PermissionType.ADMINSPACE)
            .toString();
    List<String> permissions = new ArrayList<>();
    Iterator<Map.Entry<String, IDType>> iter = Utils.getACLForAdmins().entrySet().iterator();
    while (iter.hasNext()) {
      Map.Entry<String, IDType> entry = iter.next();
      permissions.add(new StringBuilder(all).append(":").append(entry.getValue()).append(":").append(entry.getKey()).toString());
    }
    if (PortalConfig.PORTAL_TYPE.equals(wikiType)) {
      UserPortalConfigService service = ExoContainerContext.getCurrentContainer()
              .getComponentInstanceOfType(UserPortalConfigService.class);
      PortalConfig portalConfig = service.getUserPortalConfig(wikiOwner, null).getPortalConfig();
      String portalEditClause = new StringBuilder(all).append(":")
              .append(IDType.MEMBERSHIP)
              .append(":")
              .append(portalConfig.getEditPermission())
              .toString();
      if (!permissions.contains(portalEditClause)) {
        permissions.add(portalEditClause);
      }
      permissions.add(new StringBuilder(view).append(":").append(IDType.USER).append(":any").toString());
    } else if (PortalConfig.GROUP_TYPE.equals(wikiType)) {
      UserACL userACL = ExoContainerContext.getCurrentContainer().getComponentInstanceOfType(UserACL.class);
      String makableMTClause = new StringBuilder(all).append(":")
              .append(IDType.MEMBERSHIP)
              .append(":")
              .append(userACL.getMakableMT())
              .append(":")
              .append(wikiOwner)
              .toString();
      if (!permissions.contains(makableMTClause)) {
        permissions.add(makableMTClause);
      }
      String ownerClause = new StringBuilder(viewEdit).append(":")
              .append(IDType.MEMBERSHIP)
              .append(":*:")
              .append(wikiOwner)
              .toString();
      if (!permissions.contains(ownerClause)) {
        permissions.add(ownerClause);
      }
    } else if (PortalConfig.USER_TYPE.equals(wikiType)) {
      String ownerClause = new StringBuilder(all).append(":").append(IDType.USER).append(":").append(wikiOwner).toString();
      if (!permissions.contains(ownerClause)) {
        permissions.add(ownerClause);
      }
    }
    return permissions;
  }

  @Override
  public void setWikiPermission(String wikiType, String wikiOwner, List<PermissionEntry> permissionEntries) throws Exception {
    WikiImpl wiki = fetchWikiImpl(wikiType, wikiOwner, false);
    List<String> permissions = new ArrayList<>();
    HashMap<String, String[]> permMap = new HashMap<>();
    for (PermissionEntry entry : permissionEntries) {
      StringBuilder actions = new StringBuilder();
      org.exoplatform.wiki.service.Permission[] pers = entry.getPermissions();
      List<String> permlist = new ArrayList<>();
      // Permission strings has the format:
      // VIEWPAGE,EDITPAGE,ADMINPAGE,ADMINSPACE:USER:john
      // VIEWPAGE:GROUP:/platform/users
      // VIEWPAGE,EDITPAGE,ADMINPAGE,ADMINSPACE:MEMBERSHIP:manager:/platform/administrators
      for (int i = 0; i < pers.length; i++) {
        org.exoplatform.wiki.service.Permission perm = pers[i];
        if (perm.isAllowed()) {
          actions.append(perm.getPermissionType().toString());
          if (i < pers.length - 1) {
            actions.append(",");
          }

          if (perm.getPermissionType().equals(PermissionType.VIEWPAGE)) {
            permlist.add(org.exoplatform.services.jcr.access.PermissionType.READ);
          } else if (perm.getPermissionType().equals(PermissionType.EDITPAGE)) {
            permlist.add(org.exoplatform.services.jcr.access.PermissionType.ADD_NODE);
            permlist.add(org.exoplatform.services.jcr.access.PermissionType.REMOVE);
            permlist.add(org.exoplatform.services.jcr.access.PermissionType.SET_PROPERTY);
          }
        }
      }
      if (actions.toString().length() > 0) {
        actions.append(":").append(entry.getIdType()).append(":").append(entry.getId());
        permissions.add(actions.toString());
      }
      if (permlist.size() > 0) {
        permMap.put(entry.getId(), permlist.toArray(new String[permlist.size()]));
      }
    }
    wiki.setWikiPermissions(permissions);
    // TODO: study performance
    updateAllPagesPermissions(wikiType, wikiOwner, permMap);
  }

  @Override
  public List<Page> getRelatedPagesOfPage(Page page) throws Exception {
    List<Page> relatedPages = new ArrayList<>();

    PageImpl pageImpl = fetchPageImpl(page.getWikiType(), page.getWikiOwner(), page.getName());
    List<PageImpl> relatedPageImpls = pageImpl.getRelatedPages();
    for (PageImpl relatedPageImpl : relatedPageImpls) {
      relatedPages.add(convertPageImplToPage(relatedPageImpl));
    }
    return relatedPages;
  }

  @Override
  public Page getRelatedPage(String wikiType, String wikiOwner, String pageId) throws Exception {
    WikiImpl wiki = fetchWikiImpl(wikiType, wikiOwner, false);
    LinkRegistry linkRegistry = wiki.getLinkRegistry();
    LinkEntry oldLinkEntry = linkRegistry.getLinkEntries().get(getLinkEntryName(wikiType, wikiOwner, pageId));
    LinkEntry newLinkEntry = null;
    if (oldLinkEntry != null) {
      newLinkEntry = oldLinkEntry.getNewLink();
    }
    int circularFlag = CIRCULAR_RENAME_FLAG;// To deal with old circular data if it is existed
    while (newLinkEntry != null && !newLinkEntry.equals(oldLinkEntry) && circularFlag > 0) {
      oldLinkEntry = newLinkEntry;
      newLinkEntry = oldLinkEntry.getNewLink();
      circularFlag--;
    }
    if (newLinkEntry == null) {
      return null;
    }
    if (circularFlag == 0) {
      // Find link entry mapped with an existed page in old circular data
      circularFlag = CIRCULAR_RENAME_FLAG;
      while (circularFlag > 0) {
        if (getPageWithLinkEntry(newLinkEntry) != null) {
          break;
        }
        newLinkEntry = newLinkEntry.getNewLink();
        circularFlag--;
      }
      // Break old circular data
      if (circularFlag > 0) {
        newLinkEntry.setNewLink(newLinkEntry);
      }
    }
    return getPageWithLinkEntry(newLinkEntry);
  }

  @Override
  public void addRelatedPage(Page page, Page relatedPage) throws Exception {
    PageImpl pageImpl = fetchPageImpl(page.getWikiType(), page.getWikiOwner(), page.getName());
    PageImpl relatedPageImpl = fetchPageImpl(relatedPage.getWikiType(), relatedPage.getWikiOwner(), relatedPage.getName());

    pageImpl.addRelatedPage(relatedPageImpl);
  }

  @Override
  public void removeRelatedPage(Page page, Page relatedPage) throws Exception {
    PageImpl pageImpl = fetchPageImpl(page.getWikiType(), page.getWikiOwner(), page.getName());
    PageImpl relatedPageImpl = fetchPageImpl(relatedPage.getWikiType(), relatedPage.getWikiOwner(), relatedPage.getName());

    pageImpl.removeRelatedPage(relatedPageImpl);
  }

  @Override
  public Page getExsitedOrNewDraftPageById(String wikiType, String wikiOwner, String pageId, String username) throws Exception {
    // if this is ANONIM then use draft in DraftNewPagesContainer
    if (IdentityConstants.ANONIM.equals(username)) {
      Model model = getModel();
      WikiStore wStore = model.getWikiStore();
      PageImpl draftNewPagesContainer = wStore.getDraftNewPagesContainer();
      PageImpl draftPage = draftNewPagesContainer.getChildPages().get(pageId);
      if (draftPage == null) {
        draftPage = wStore.createPage();
        draftPage.setName(pageId);
        draftNewPagesContainer.addPublicPage(draftPage);
      }
      return convertPageImplToPage(draftPage);
    }

    // check to get draft if exist
    Model model = getModel();
    UserWiki userWiki = null;

    // Check if in the case that access to wiki page by rest service of xwiki
    if ((username == null) && (pageId.contains(Utils.SPLIT_TEXT_OF_DRAFT_FOR_NEW_PAGE))) {
      String[] texts = pageId.split(Utils.SPLIT_TEXT_OF_DRAFT_FOR_NEW_PAGE);
      username = texts[0];
      WikiStoreImpl wStore = (WikiStoreImpl) model.getWikiStore();
      WikiContainer<UserWiki> userWikiContainer = wStore.getWikiContainer(WikiType.USER);
      userWiki = userWikiContainer.getWiki(username, true);
      Collection<PageImpl> childPages = userWiki.getDraftPagesContainer().getChildrenByRootPermission().values();

      // Change collection to List
      for (PageImpl pageImpl : childPages) {
        if (pageImpl.getName().equals(pageId)) {
          return convertPageImplToPage(pageImpl);
        }
      }
    } else {
      // Get draft page
      DraftPage draftPage = getDraft(pageId, username);
      if (draftPage != null) {
        return draftPage;
      }
    }

    // Get draft page container
    if (userWiki == null) {
      userWiki = (UserWiki) fetchWikiImpl(PortalConfig.USER_TYPE, username, false);
    }
    PageImpl draftPagesContainer = userWiki.getDraftPagesContainer();

    // Create new draft
    DraftPageImpl draftPageImpl = userWiki.createDraftPage();
    draftPageImpl.setName(pageId);
    draftPagesContainer.addWikiPage(draftPageImpl);
    draftPageImpl.setNewPage(true);
    draftPageImpl.setTargetPage(null);
    draftPageImpl.setTargetRevision("1");

    // Put any permisison to access by xwiki rest service
    HashMap<String, String[]> permissions = draftPageImpl.getPermission();
    permissions.put(IdentityConstants.ANY, new String[]{org.exoplatform.services.jcr.access.PermissionType.READ});
    draftPageImpl.setPermission(permissions);
    return convertPageImplToPage(draftPageImpl);
  }

  @Override
  public DraftPage getDraft(WikiPageParams param, String username) throws Exception {
    if (IdentityConstants.ANONIM.equals(username)) {
      return null;
    }

    if ((param.getPageId() == null) || (param.getOwner() == null) || (param.getType() == null)) {
      return null;
    }

    PageImpl targetPage = fetchPageImpl(param.getType(), param.getOwner(), param.getPageId());
    if ((param.getPageId() == null) || (targetPage == null)) {
      return null;
    }

    // Get all draft pages
    UserWiki userWiki = (UserWiki) fetchWikiImpl(PortalConfig.USER_TYPE, username, true);
    Collection<PageImpl> childPages = userWiki.getDraftPagesContainer().getChildPages().values();

    // Find the lastest draft of target page
    DraftPageImpl lastestDraft = null;
    for (PageImpl draft : childPages) {
      DraftPageImpl draftPage = (DraftPageImpl) draft;
      // If this draft is use for target page
      if (draftPage.getTargetPage() != null && !draftPage.isNewPage() && draftPage.getTargetPage().equals(targetPage.getJCRPageNode().getUUID())) {
        // Compare and get the lastest draft
        if ((lastestDraft == null) || (lastestDraft.getUpdatedDate().getTime() < draftPage.getUpdatedDate().getTime())) {
          lastestDraft = draftPage;
        }
      }
    }

    return convertDraftPageImplToDraftPage(lastestDraft);
  }


  @Override
  public DraftPage getLastestDraft(String username) throws Exception {
    // Get all draft pages
    UserWiki userWiki = (UserWiki) fetchWikiImpl(PortalConfig.USER_TYPE, username, true);
    Collection<PageImpl> childPages = userWiki.getDraftPagesContainer().getChildPages().values();

    // Find the lastest draft
    DraftPageImpl lastestDraft = null;
    for (PageImpl draft : childPages) {
      DraftPageImpl draftPage = (DraftPageImpl) draft;
      // Compare and get the lastest draft
      if ((lastestDraft == null) || (lastestDraft.getUpdatedDate().getTime() < draftPage.getUpdatedDate().getTime())) {
        lastestDraft = draftPage;
      }
    }
    return convertDraftPageImplToDraftPage(lastestDraft);
  }

  @Override
  public DraftPage getDraft(String draftName, String username) throws Exception {
    List<DraftPage> drafts = getDraftPagesOfUser(username);
    for (DraftPage draftPage : drafts) {
      if (draftPage.getName().equals(draftName)) {
        return draftPage;
      }
    }

    return null;
  }

  @Override
  public List<DraftPage> getDraftPagesOfUser(String username) throws Exception {
    List<DraftPage> draftPages = new ArrayList<>();

    // Get all draft of user
    UserWiki userWiki = (UserWiki) getModel().getWikiStore().getWiki(WikiType.USER, username);
    Collection<PageImpl> childPages = userWiki.getDraftPagesContainer().getChildPages().values();

    // Change collection to List
    for (PageImpl page : childPages) {
      DraftPage draftPage = convertDraftPageImplToDraftPage((DraftPageImpl) page);
      draftPage.setWikiType(userWiki.getType());
      draftPage.setWikiOwner(userWiki.getOwner());
      draftPages.add(draftPage);
    }

    return draftPages;
  }

  @Override
  public void createDraftPageForUser(DraftPage draftPage, String username) throws Exception {
    Model model = getModel();
    WikiStore wikiStore = model.getWikiStore();

    UserWiki userWiki = (UserWiki) wikiStore.getWiki(WikiType.USER, username);
    PageImpl draftPagesContainer = userWiki.getDraftPagesContainer();

    // Create draft page
    DraftPageImpl draftPageImpl = userWiki.createDraftPage();
    draftPageImpl.setName(draftPage.getName());
    draftPagesContainer.addWikiPage(draftPageImpl);
    draftPageImpl.setNewPage(draftPage.isNewPage());
    draftPageImpl.setTargetPage(draftPage.getTargetPage());
    draftPageImpl.setTargetRevision(draftPage.getTargetRevision());

    model.save();
  }

  @Override
  public PageList<SearchResult> search(WikiSearchData data) throws Exception {
    List<SearchResult> resultList = new ArrayList<>();
    long numberOfSearchForTitleResult = 0;

    Model model = getModel();
    WikiStoreImpl wStore = (WikiStoreImpl) model.getWikiStore();
    ChromatticSession session = wStore.getSession();

    if (!StringUtils.isEmpty(data.getTitle())) {
      // Search for title
      String statement = data.getStatementForSearchingTitle();
      QueryImpl q = (QueryImpl) ((ChromatticSessionImpl) session).getDomainSession().getSessionWrapper().createQuery(statement);
      if(data.getOffset() > 0) {
        q.setOffset(data.getOffset());
      }
      if(data.getLimit() > 0) {
        q.setLimit(data.getLimit());
      }
      QueryResult result = q.execute();
      RowIterator iter = result.getRows();
      numberOfSearchForTitleResult = iter.getSize();
      if (numberOfSearchForTitleResult > 0) {       
        while (iter.hasNext()) {
          SearchResult tempResult = getResult(iter.nextRow(), data);
          // If contains, merges with the exist
          if (tempResult != null && !isContains(resultList, tempResult)) {
            resultList.add(tempResult);
          }
        }
      }
    }
    
    // if we have enough result then return
    if ((resultList.size() >= data.getLimit()) || StringUtils.isEmpty(data.getContent())) {
      return new ObjectPageList<>(resultList, resultList.size());
    }
    // Search for wiki content
    long searchForContentOffset = data.getOffset();
    long searchForContentLimit = data.getLimit() - numberOfSearchForTitleResult;
    if (data.getLimit() == Integer.MAX_VALUE) {
      searchForContentLimit = Integer.MAX_VALUE;
    }
    
    if (searchForContentOffset >= 0 && searchForContentLimit > 0) {
      String statement = data.getStatementForSearchingContent();
      QueryImpl q = (QueryImpl) ((ChromatticSessionImpl) session).getDomainSession().getSessionWrapper().createQuery(statement);
      q.setOffset(searchForContentOffset);
      q.setLimit(searchForContentLimit);
      QueryResult result = q.execute();
      RowIterator iter = result.getRows();
      while (iter.hasNext()) {
        SearchResult tempResult = getResult(iter.nextRow(), data);
        // If contains, merges with the exist
        if (tempResult != null && !isContains(resultList, tempResult) && !isDuplicateTitle(resultList, tempResult)) {
          resultList.add(tempResult);
        }
      }
    }
    // Return all the result
    return new ObjectPageList<>(resultList, resultList.size());
  }

  @Override
  public List<SearchResult> searchRenamedPage(WikiSearchData data) throws Exception {
    Model model = getModel();
    WikiStoreImpl wStore = (WikiStoreImpl) model.getWikiStore();
    ChromatticSession session = wStore.getSession();

    List<SearchResult> resultList = new ArrayList<>() ;
    String statement = data.getStatementForRenamedPage() ;
    Query q = ((ChromatticSessionImpl)session).getDomainSession().getSessionWrapper().createQuery(statement);
    QueryResult result = q.execute();
    NodeIterator iter = result.getNodes() ;
    while(iter.hasNext()) {
      try {
        resultList.add(getResult(iter.nextNode()));
      } catch (Exception e) {
        log.debug("Failed to add item search result", e);
      }
    }
    return resultList ;
  }

  @Override
  public Page getPageOfAttachment(Attachment attachment) throws Exception {
    // TODO
    throw new UnsupportedOperationException();
  }

  @Override
  public InputStream getAttachmentAsStream(String path) throws Exception {
    Model model = getModel();
    WikiStoreImpl wStore = (WikiStoreImpl) model.getWikiStore();
    ChromatticSession session = wStore.getSession();

    Node attContent = (Node)session.getJCRSession().getItem(path) ;
    return attContent.getProperty(WikiNodeType.Definition.DATA).getStream() ;
  }

  @Override
  public Object findByPath(String path, String objectNodeType) {
    try {
      Model model = getModel();
      WikiStoreImpl wStore = (WikiStoreImpl) model.getWikiStore();
      if (WikiNodeType.WIKI_PAGE.equals(objectNodeType)) {
        return wStore.getSession().findByPath(PageImpl.class, path);
      } else if (WikiNodeType.WIKI_ATTACHMENT.equals(objectNodeType)) {
        return wStore.getSession().findByPath(AttachmentImpl.class, path);
      } else if (WikiNodeType.WIKI_TEMPLATE.equals(objectNodeType)) {
        return wStore.getSession().findByPath(Template.class, path);
      }
    } catch (Exception e) {
      log.error("Can't find Object", e);
    }
    return null;
  }

  @Override
  public Page getHelpSyntaxPage(String syntaxId, List<ValuesParam> syntaxHelpParams, ConfigurationManager configurationManager) throws Exception {
    Model model = getModel();
    WikiStoreImpl wStore = (WikiStoreImpl) model.getWikiStore();
    HelpPage helpPageByChromattic = wStore.getHelpPageByChromattic();

    if(helpPageByChromattic == null || wStore.getHelpPagesContainer().getChildPages().size() == 0) {
      createHelpPages(syntaxHelpParams, configurationManager);
    }

    Iterator<PageImpl> syntaxPageIterator = wStore.getHelpPagesContainer()
            .getChildPages()
            .values()
            .iterator();
    while (syntaxPageIterator.hasNext()) {
      PageImpl syntaxPage = syntaxPageIterator.next();
      if (syntaxPage.getSyntax().equals(syntaxId)) {
        return convertPageImplToPage(syntaxPage);
      }
    }
    return null;
  }

  @Override
  public Page getEmotionIconsPage() throws Exception {
    Model model = getModel();
    WikiStoreImpl wStore = (WikiStoreImpl) model.getWikiStore();
    return convertPageImplToPage(wStore.getEmotionIconsPage());
  }

  private synchronized void createHelpPages(List<ValuesParam> syntaxHelpParams, ConfigurationManager configurationManager) throws Exception {
    Model model = getModel();
    WikiStoreImpl wStore = (WikiStoreImpl) model.getWikiStore();
    PageImpl helpPage = wStore.getHelpPagesContainer();
    if (helpPage.getChildPages().size() == 0) {
      for (ValuesParam syntaxhelpParam : syntaxHelpParams) {
        try {
          String syntaxName = syntaxhelpParam.getName();
          List<String> syntaxValues = syntaxhelpParam.getValues();
          String shortFilePath = syntaxValues.get(0);
          String fullFilePath = syntaxValues.get(1);
          InputStream shortFile = configurationManager.getInputStream(shortFilePath);
          InputStream fullFile = configurationManager.getInputStream(fullFilePath);
          HelpPage syntaxPage = addSyntaxPage(wStore, helpPage, syntaxName, shortFile, " Short help Page");
          addSyntaxPage(wStore, syntaxPage, syntaxName, fullFile, " Full help Page");
          wStore.getSession().save();
        } catch (Exception e) {
          log.error("Can not create Help page", e);
        }
      }
    }
  }

  @Override
  public String getPortalOwner() {
    Model model = getModel();
    WikiStoreImpl wStore = (WikiStoreImpl) model.getWikiStore();
    List<WikiImpl> portalWikis = new ArrayList<>(wStore.getWikiContainer(WikiType.PORTAL).getAllWikis());
    if (portalWikis.size() > 0) {
      return portalWikis.get(0).getOwner();
    }
    return null;
  }

  @Override
  public boolean hasAdminSpacePermission(String wikiType, String owner, Identity user) throws Exception {
    List<AccessControlEntry> aces = getAccessControls(wikiType, owner);
    AccessControlList acl = new AccessControlList(owner, aces);
    String[] permission = new String[]{PermissionType.ADMINSPACE.toString()};
    return Utils.hasPermission(acl, permission, user);
  }

  @Override
  public boolean hasAdminPagePermission(String wikiType, String owner, Identity user) throws Exception {
    List<AccessControlEntry> aces = getAccessControls(wikiType, owner);
    AccessControlList acl = new AccessControlList(owner, aces);
    String[] permission = new String[]{PermissionType.ADMINPAGE.toString()};
    return Utils.hasPermission(acl, permission, user);
  }

  @Override
  public boolean hasPermissionOnPage(Page page, PermissionType permissionType, Identity user) throws Exception {
    PageImpl pageImpl = fetchPageImpl(page.getWikiType(), page.getWikiOwner(), page.getName());
    return pageImpl.hasPermission(permissionType, user);
  }

  private List<AccessControlEntry> getAccessControls(String wikiType, String wikiOwner) throws Exception {
    List<AccessControlEntry> aces = new ArrayList<>();
    try {
      List<PermissionEntry> permissionEntries = getWikiPermission(wikiType, wikiOwner);
      for (PermissionEntry perm : permissionEntries) {
        org.exoplatform.wiki.service.Permission[] permissions = perm.getPermissions();
        List<String> actions = new ArrayList<>();
        for (org.exoplatform.wiki.service.Permission permission : permissions) {
          if (permission.isAllowed()) {
            actions.add(permission.getPermissionType().toString());
          }
        }

        for (String action : actions) {
          aces.add(new AccessControlEntry(perm.getId(), action));
        }
      }
    } catch (Exception e) {
      if (log.isDebugEnabled()) {
        log.debug("failed in method getAccessControls:", e);
      }
    }
    return aces;
  }

  @Override
  public List<PageVersion> getVersionsOfPage(Page page) throws Exception {
    PageImpl pageImpl = fetchPageImpl(page.getWikiType(), page.getWikiOwner(), page.getName());

    List<PageVersion> versions = new ArrayList<>();
    VersionableMixin versionableMixin = pageImpl.getVersionableMixin();
    if(versionableMixin != null) {
      for (NTVersion version : versionableMixin.getVersionHistory()) {
        if (!(WikiNodeType.Definition.ROOT_VERSION.equals(version.getName()))) {
          PageVersion pageVersion = new PageVersion();
          pageVersion.setName(version.getName());
          pageVersion.setAuthor(version.getNTFrozenNode().getAuthor());
          pageVersion.setCreatedDate(version.getCreated());
          pageVersion.setUpdatedDate(version.getNTFrozenNode().getUpdatedDate());
          //pageVersion.setPredecessors(version.getPredecessors());
          //pageVersion.setSuccessors(version.getSuccessors());
          pageVersion.setContent(version.getNTFrozenNode().getContentString());
          pageVersion.setComment(version.getNTFrozenNode().getComment());
          versions.add(pageVersion);
        }
      }
    }
    Collections.sort(versions, new VersionNameComparatorDesc());
    return versions;
  }

  @Override
  public void addPageVersion(Page page) throws Exception {
    PageImpl pageImpl = fetchPageImpl(page.getWikiType(), page.getWikiOwner(), page.getName());
    if(pageImpl.getVersionableMixin() == null) {
      pageImpl.makeVersionable();
    }
    pageImpl.checkin();
    pageImpl.checkout();
  }

  @Override
  public void updatePage(Page page) throws Exception {
    Model model = getModel();

    PageImpl pageImpl = fetchPageImpl(page.getWikiType(), page.getWikiOwner(), page.getName());
    pageImpl.setTitle(page.getTitle());
    pageImpl.setSyntax(page.getSyntax());
    pageImpl.setPermission(page.getPermission());
    pageImpl.setURL(page.getUrl());
    pageImpl.getContent().setText(page.getContent().getText());
    pageImpl.setComment(page.getComment());

    model.save();
  }

  private HelpPage addSyntaxPage(WikiStoreImpl wStore,
                                 PageImpl parentPage,
                                 String name,
                                 InputStream content,
                                 String type) throws Exception {
    StringBuilder stringContent = new StringBuilder();
    BufferedReader bufferReader;
    String tempLine;
    bufferReader = new BufferedReader(new InputStreamReader(content));
    while ((tempLine = bufferReader.readLine()) != null) {
      stringContent.append(tempLine).append("\n");
    }

    HelpPage syntaxPage = wStore.createHelpPage();
    String realName = name.replace("/", "");
    syntaxPage.setName(realName + type);
    parentPage.addPublicPage(syntaxPage);
    AttachmentImpl pageContent = syntaxPage.getContent();
    syntaxPage.setTitle(realName + type);
    pageContent.setText(stringContent.toString());
    syntaxPage.setSyntax(name);
    syntaxPage.setNonePermission();
    content.close();
    bufferReader.close();
    return syntaxPage;
  }

  private boolean isDuplicateTitle(List<SearchResult> list, SearchResult result) {
    for (SearchResult searchResult : list) {
      if (result.getTitle().equals(searchResult.getTitle())) {
        return true;
      }
    } 
    return false;
  }

  private void updateAllPagesPermissions(String wikiType, String wikiOwner, HashMap<String, String[]> permMap) throws Exception {
    PageImpl page = fetchWikiImpl(wikiType, wikiOwner, false).getWikiHome();
    Queue<PageImpl> queue = new LinkedList<>();
    queue.add(page);
    while (queue.peek() != null) {
      PageImpl p = queue.poll();
      if (!p.getOverridePermission()) {
        p.setPermission(permMap);
        p.setUpdateAttachmentMixin(null);
      }
      Iterator<PageImpl> iter = p.getChildPages().values().iterator();
      while (iter.hasNext()) {
        queue.add(iter.next());
      }
    }
  }

  private Page getPageWithLinkEntry(LinkEntry entry) throws Exception {
    String linkEntryAlias = entry.getAlias();
    String[] splits = linkEntryAlias.split("@");
    String wikiType = splits[0];
    String wikiOwner = splits[1];
    String pageId = linkEntryAlias.substring((wikiType + "@" + wikiOwner + "@").length());
    return getPageOfWikiByName(wikiType, wikiOwner, pageId);
  }

  private String getLinkEntryName(String wikiType, String wikiOwner, String pageId) {
    if (PortalConfig.GROUP_TYPE.equals(wikiType)) {
      wikiOwner = wikiOwner.replace("/", "-");
    }
    return wikiType + "@" + wikiOwner + "@" + pageId;
  }

  private String getLinkEntryAlias(String wikiType, String wikiOwner, String pageId) {
    return wikiType + "@" + wikiOwner + "@" + pageId;
  }

  private void setFullPermissionForOwner(PageImpl page, String owner) throws Exception {
    ConversationState conversationState = ConversationState.getCurrent();

    if (conversationState != null) {
      HashMap<String, String[]> permissions = page.getPermission();
      permissions.put(conversationState.getIdentity().getUserId(), org.exoplatform.services.jcr.access.PermissionType.ALL);
      page.setPermission(permissions);
    }
  }

  private Model getModel() {
    return mowService.getModel();
  }
  
  private SearchResult getResult(Row row, WikiSearchData data) throws Exception {
    String type = row.getValue(WikiNodeType.Definition.PRIMARY_TYPE).getString();
    String path = row.getValue(WikiNodeType.Definition.PATH).getString();
    
    String title = StringUtils.EMPTY;
    String excerpt = StringUtils.EMPTY;
    long jcrScore = row.getValue("jcr:score").getLong();
    Calendar updateDate = GregorianCalendar.getInstance();
    Calendar createdDate = GregorianCalendar.getInstance();
    PageImpl page = null;
    if (WikiNodeType.WIKI_ATTACHMENT.equals(type)) {
      // Transform to Attachment result
      type = WikiNodeType.WIKI_ATTACHMENT.toString();
      if(!path.endsWith(WikiNodeType.Definition.CONTENT)){
        AttachmentImpl searchAtt = (AttachmentImpl) Utils.getObject(path, WikiNodeType.WIKI_ATTACHMENT);
        updateDate = searchAtt.getUpdatedDate();
        page = searchAtt.getParentPage();
        createdDate.setTime(page.getCreatedDate());
        title = page.getTitle();
      } else {
        String pagePath = path.substring(0, path.lastIndexOf("/" + WikiNodeType.Definition.CONTENT));
        type = WikiNodeType.WIKI_PAGE_CONTENT.toString();
        page = (PageImpl) Utils.getObject(pagePath, WikiNodeType.WIKI_PAGE);
        title = page.getTitle();
        updateDate.setTime(page.getUpdatedDate());
        createdDate.setTime(page.getCreatedDate());
      }
    } else if (WikiNodeType.WIKI_PAGE.equals(type)) {
      page = (PageImpl) Utils.getObject(path, type);
      updateDate.setTime(page.getUpdatedDate());
      createdDate.setTime(page.getCreatedDate());
      title = page.getTitle();
    } else {
      return null;
    }
    
    //get the excerpt from row result
    excerpt = getExcerpt(row, type);

    if (page == null || !page.hasPermission(PermissionType.VIEWPAGE)) {
      return null;
    }
    
    SearchResult result = new SearchResult(excerpt, title, path, type, updateDate, createdDate);
    result.setUrl(page.getURL());
    result.setJcrScore(jcrScore);
    return result;
  }

  private Wiki getWikiWithoutPermission(String wikiType, String owner, Model model) throws Exception {
    WikiStore wStore = model.getWikiStore();
    WikiImpl wiki = null;
    try {
      if (PortalConfig.PORTAL_TYPE.equals(wikiType)) {
        WikiContainer<PortalWiki> portalWikiContainer = wStore.getWikiContainer(WikiType.PORTAL);
        wiki = portalWikiContainer.getWiki(owner, true);
      } else if (PortalConfig.GROUP_TYPE.equals(wikiType)) {
        WikiContainer<GroupWiki> groupWikiContainer = wStore.getWikiContainer(WikiType.GROUP);
        wiki = groupWikiContainer.getWiki(owner, true);
      } else if (PortalConfig.USER_TYPE.equals(wikiType)) {
        WikiContainer<UserWiki> userWikiContainer = wStore.getWikiContainer(WikiType.USER);
        wiki = userWikiContainer.getWiki(owner, true);
      }
    } catch (Exception e) {
      if (log.isDebugEnabled()) {
        log.debug("[WikiService] Cannot get wiki " + wikiType + ":" + owner, e);
      }
    }
    return convertWikiImplToWiki(wiki);
  }

  private void processCircularRename(LinkEntry entry, LinkEntry newEntry) {
    // Check circular rename
    boolean isCircular = true;
    int circularFlag = CIRCULAR_RENAME_FLAG;// To deal with old circular data if it is existed
    LinkEntry checkEntry = newEntry;
    while (!checkEntry.equals(entry) && circularFlag > 0) {
      checkEntry = checkEntry.getNewLink();
      if (checkEntry == null || (checkEntry.equals(checkEntry.getNewLink()) && !checkEntry.equals(entry))) {
        isCircular = false;
        break;
      }
      circularFlag--;
    }
    if (!isCircular || circularFlag == 0) {
      entry.setNewLink(newEntry);
    } else {
      LinkEntry nextEntry = newEntry.getNewLink();
      while (!nextEntry.equals(newEntry)) {
        LinkEntry deletedEntry = nextEntry;
        nextEntry = nextEntry.getNewLink();
        if (!nextEntry.equals(deletedEntry)) {
          deletedEntry.remove();
        } else {
          deletedEntry.remove();
          break;
        }
      }
    }
    newEntry.setNewLink(newEntry);
  }

  /**
   * gets except of row result based on specific properties, but all to get nice excerpt
   * @param row the result row
   * @param type the result type
   * @return the excerpt
   * @throws ItemNotFoundException
   * @throws RepositoryException
   */
  private String getExcerpt(Row row, String type) throws ItemNotFoundException, RepositoryException {
    StringBuilder ret = new StringBuilder();
    String[] properties = (WikiNodeType.WIKI_PAGE_CONTENT.equals(type) || WikiNodeType.WIKI_ATTACHMENT.equals(type)) ? 
                          new String[]{"."} :
                          new String[]{"title", "url"};
    for (String prop : properties) {
      Value excerptValue = row.getValue("rep:excerpt(" + prop + ")");
      if (excerptValue != null) {
        ret.append(excerptValue.getString()).append("...");
      }
    }
    if (ret.length() > MAX_EXCERPT_LENGTH) {
      return ret.substring(0, MAX_EXCERPT_LENGTH) + "...";
    }
    return ret.toString();
  }
  
  private SearchResult getResult(Node node)throws Exception {
    SearchResult result = new SearchResult() ;
    result.setPageName(node.getName()) ;
    String title = node.getProperty(WikiNodeType.Definition.TITLE).getString();
    InputStream data = node.getNode(WikiNodeType.Definition.CONTENT).getNode(WikiNodeType.Definition.ATTACHMENT_CONTENT).getProperty(WikiNodeType.Definition.DATA).getStream();
    byte[] bytes = IO.getBytes(data);
    String content = new String(bytes, "UTF-8");
    if(content.length() > 100) content = content.substring(0, 100) + "...";
    result.setExcerpt(content) ;
    result.setTitle(title) ;
    return result ;
  }
  
  private boolean isContains(List<SearchResult> list, SearchResult result) throws Exception {
    AttachmentImpl att = null;
    PageImpl page = null;
    if (WikiNodeType.WIKI_ATTACHMENT.equals(result.getType())) {
      att = (AttachmentImpl) Utils.getObject(result.getPath(), WikiNodeType.WIKI_ATTACHMENT);
    } else if (WikiNodeType.WIKI_ATTACHMENT_CONTENT.equals(result.getType())) {
      String attPath = result.getPath().substring(0, result.getPath().lastIndexOf("/"));
      att = (AttachmentImpl) Utils.getObject(attPath, WikiNodeType.WIKI_ATTACHMENT);
    } else if(WikiNodeType.WIKI_PAGE.equals(result.getType()) || WikiNodeType.WIKI_HOME.equals(result.getType())){
      page = (PageImpl) Utils.getObject(result.getPath(), WikiNodeType.WIKI_PAGE);
    } else if (WikiNodeType.WIKI_PAGE_CONTENT.equals(result.getType())) {
      att = (AttachmentImpl) Utils.getObject(result.getPath(), WikiNodeType.WIKI_ATTACHMENT);
      page = att.getParentPage();
    }
    if (att != null || page != null) {
      Iterator<SearchResult> iter = list.iterator();
      while (iter.hasNext()) {
        SearchResult child = iter.next();
        if (WikiNodeType.WIKI_ATTACHMENT.equals(child.getType()) || WikiNodeType.WIKI_PAGE_CONTENT.equals(child.getType())) {
          AttachmentImpl tempAtt = (AttachmentImpl) Utils.getObject(child.getPath(), WikiNodeType.WIKI_ATTACHMENT);
          if (att != null && att.equals(tempAtt)) {
            // Merge data
            if (child.getExcerpt()==null && result.getExcerpt()!=null ){
              child.setExcerpt(result.getExcerpt());
            }
            return true;
          }               
          if (page != null && page.getName().equals(tempAtt.getParentPage())) {
            return true;
          }     
        } else if (WikiNodeType.WIKI_PAGE.equals(child.getType())) {
          if (page != null && page.getPath().equals(child.getPath())) {
            iter.remove();
            return false;
          }
        }
      }
    }
    return false;
  }

  @Override
  public List<TemplateSearchResult> searchTemplate(TemplateSearchData data) throws Exception {
    Model model = getModel();
    WikiStoreImpl wStore = (WikiStoreImpl) model.getWikiStore();
    ChromatticSession session = wStore.getSession();

    List<TemplateSearchResult> resultList = new ArrayList<>();
    String statement = data.getStatementForSearchingTitle();
    Query q = ((ChromatticSessionImpl)session).getDomainSession().getSessionWrapper().createQuery(statement);
    QueryResult result = q.execute();
    RowIterator iter = result.getRows();
    while (iter.hasNext()) {
      TemplateSearchResult tempResult = getTemplateResult(iter.nextRow());
      resultList.add(tempResult);
    }
   return resultList;
  }

  private TemplateSearchResult getTemplateResult(Row row) throws Exception {
    String type = row.getValue(WikiNodeType.Definition.PRIMARY_TYPE).getString();

    String path = row.getValue(WikiNodeType.Definition.PATH).getString();
    String title = (row.getValue(WikiNodeType.Definition.TITLE) == null ? null : row.getValue(WikiNodeType.Definition.TITLE).getString());
    
    TemplateImpl templateImpl = (TemplateImpl) Utils.getObject(path, WikiNodeType.WIKI_PAGE);
    String description = templateImpl.getDescription();
    TemplateSearchResult result = new TemplateSearchResult(templateImpl.getName(),
                                                           title,
                                                           path,
                                                           type,
                                                           null,
                                                           null,
                                                           description);
    return result;
  }

  /**
   * Fetch a WikiImpl object with Chrommatic
   * @param wikiType
   * @param wikiOwner
   * @param hasAdminPermission
   * @return
   */
  private WikiImpl fetchWikiImpl(String wikiType, String wikiOwner, boolean hasAdminPermission) throws Exception {
    WikiStoreImpl wStore = (WikiStoreImpl) getModel().getWikiStore();
    WikiImpl wiki = null;
    try {
      if (PortalConfig.PORTAL_TYPE.equals(wikiType)) {
        WikiContainer<PortalWiki> portalWikiContainer = wStore.getWikiContainer(WikiType.PORTAL);
        wiki = portalWikiContainer.getWiki(wikiOwner, hasAdminPermission);
      } else if (PortalConfig.GROUP_TYPE.equals(wikiType)) {
        WikiContainer<GroupWiki> groupWikiContainer = wStore.getWikiContainer(WikiType.GROUP);
        wiki = groupWikiContainer.getWiki(wikiOwner, hasAdminPermission);
      } else if (PortalConfig.USER_TYPE.equals(wikiType)) {
        WikiContainer<UserWiki> userWikiContainer = wStore.getWikiContainer(WikiType.USER);
        wiki = userWikiContainer.getWiki(wikiOwner, hasAdminPermission);
      }
      getModel().save();
    } catch (Exception e) {
      if (log.isDebugEnabled()) {
        log.debug("[WikiService] Cannot get wiki " + wikiType + ":" + wikiOwner, e);
      }
    }
    return wiki;
  }

  /**
   * Fetch a PageImpl object with Chrommatic
   * @return
   */
  private PageImpl fetchPageImpl(String wikiType, String wikiOwner, String pageName) throws Exception {
    if(pageName.equals(WikiNodeType.Definition.WIKI_HOME_NAME)) {
      WikiImpl wikiImpl = fetchWikiImpl(wikiType, wikiOwner, true);
      return wikiImpl.getWikiHome();
    }

    Model model = getModel();
    WikiStoreImpl wStore = (WikiStoreImpl) model.getWikiStore();
    ChromatticSession session = wStore.getSession();

    String statement = new WikiSearchData(wikiType, wikiOwner, pageName).getPageConstraint();

    PageImpl wikiPage = null;
    if (statement != null) {
      Iterator<PageImpl> result = session.createQueryBuilder(PageImpl.class)
              .where(statement)
              .get()
              .objects();
      if (result.hasNext()) {
        wikiPage = result.next();
      }
    }
    // TODO: still don't know reason but following code is necessary.
    if (wikiPage != null) {
      String path = wikiPage.getPath();
      if (path.startsWith("/")) {
        path = path.substring(1, path.length());
      }
      wikiPage = session.findByPath(PageImpl.class, path);
    }
    if (wikiPage != null) {
    }
    return wikiPage;
  }

  private Wiki convertWikiImplToWiki(WikiImpl wikiImpl) throws Exception {
    Wiki wiki = null;
    if(wikiImpl != null) {
      wiki = new Wiki();
      wiki.setId(wikiImpl.getName());
      wiki.setType(wikiImpl.getType());
      wiki.setOwner(wikiImpl.getOwner());
      Page wikiHome = convertPageImplToPage(wikiImpl.getWikiHome());
      wikiHome.setWikiId(wikiImpl.getName());
      wikiHome.setWikiType(wikiImpl.getType());
      wikiHome.setWikiOwner(wikiImpl.getOwner());
      wiki.setWikiHome(wikiHome);
      wiki.setPermissions(wikiImpl.getWikiPermissions());
      wiki.setDefaultPermissionsInited(wikiImpl.getDefaultPermissionsInited());
      PreferencesImpl preferencesImpl = wikiImpl.getPreferences();
      if (preferencesImpl != null) {
        WikiPreferencesSyntax wikiPreferencesSyntax = new WikiPreferencesSyntax();
        PreferencesSyntax preferencesSyntax = preferencesImpl.getPreferencesSyntax();
        if (preferencesSyntax != null) {
          wikiPreferencesSyntax.setDefaultSyntax(preferencesSyntax.getDefaultSyntax());
          wikiPreferencesSyntax.setAllowMultipleSyntaxes(preferencesSyntax.getAllowMutipleSyntaxes());
        }
        WikiPreferences wikiPreferences = new WikiPreferences();
        wikiPreferences.setPath(preferencesImpl.getPath());
        wikiPreferences.setWikiPreferencesSyntax(wikiPreferencesSyntax);
        wiki.setPreferences(wikiPreferences);
      }
    }
    return wiki;
  }

  /**
   * Utility method to convert PageImpl object to Page object
   * @param pageImpl PageImpl object to convert
   * @return
   * @throws Exception
   */
  private Page convertPageImplToPage(PageImpl pageImpl) throws Exception {
    Page page = null;
    if(pageImpl != null) {
      page = new Page();
      page.setId(pageImpl.getID());
      WikiImpl wiki = pageImpl.getWiki();
      if(wiki != null) {
        page.setWikiId(wiki.getName());
        page.setWikiType(wiki.getWikiType().toString().toLowerCase());
        page.setWikiOwner(wiki.getOwner());
      }
      page.setOwner(pageImpl.getOwner());
      page.setName(pageImpl.getName());
      page.setTitle(pageImpl.getTitle());
      page.setAuthor(pageImpl.getAuthor());
      page.setUrl(pageImpl.getURL());
      page.setCreatedDate(pageImpl.getCreatedDate());
      page.setUpdatedDate(pageImpl.getUpdatedDate());
      page.setPath(pageImpl.getPath());
      page.setComment(pageImpl.getComment());
      page.setContent(convertAttachmentImplToAttachment(pageImpl.getContent()));
      page.setSyntax(pageImpl.getSyntax());
      page.setPermission(pageImpl.getPermission());
    }
    return page;
  }

  /**
   * Utility method to convert DraftPageImpl object to DraftPage object
   * @param draftPageImpl DraftPageImpl object to convert
   * @return
   * @throws Exception
   */
  private DraftPage convertDraftPageImplToDraftPage(DraftPageImpl draftPageImpl) throws Exception {
    DraftPage draftPage = null;
    if(draftPageImpl != null) {
      draftPage = new DraftPage();
      draftPage.setId(draftPageImpl.getID());
      //draftPage.setWiki(convertWikiImplToWiki(draftPageImpl.getWiki()));
      draftPage.setOwner(draftPageImpl.getOwner());
      draftPage.setName(draftPageImpl.getName());
      draftPage.setAuthor(draftPageImpl.getAuthor());
      draftPage.setTitle(draftPageImpl.getTitle());
      draftPage.setUrl(draftPageImpl.getURL());
      draftPage.setCreatedDate(draftPageImpl.getCreatedDate());
      draftPage.setUpdatedDate(draftPageImpl.getUpdatedDate());
      draftPage.setPath(draftPageImpl.getPath());
      draftPage.setComment(draftPageImpl.getComment());
      draftPage.setContent(convertAttachmentImplToAttachment(draftPageImpl.getContent()));
      draftPage.setSyntax(draftPageImpl.getSyntax());
      draftPage.setPermission(draftPageImpl.getPermission());

      draftPage.setTargetPage(draftPageImpl.getTargetPage());
      draftPage.setTargetRevision(draftPageImpl.getTargetRevision());
      draftPage.setNewPage(draftPageImpl.isNewPage());
    }
    return draftPage;
  }

  private Attachment convertAttachmentImplToAttachment(AttachmentImpl attachmentImpl) throws Exception {
    Attachment attachment = null;
    if(attachmentImpl != null) {
      attachment = new Attachment();
      attachment.setName(attachmentImpl.getName());
      attachment.setTitle(attachmentImpl.getTitle());
      attachment.setFullTitle(attachmentImpl.getFullTitle());
      attachment.setCreator(attachmentImpl.getCreator());
      attachment.setCreatedDate(attachmentImpl.getCreatedDate());
      attachment.setUpdatedDate(attachmentImpl.getUpdatedDate());
      attachment.setContent(attachmentImpl.getContentResource().getData());
      attachment.setMimeType(attachmentImpl.getContentResource().getMimeType());
      attachment.setText(attachmentImpl.getText());
      attachment.setPermissions(attachmentImpl.getPermission());
      // TODO ???
      //attachment.setDownloadURL(attachmentImpl.getDownloadURL());
      attachment.setWeightInBytes(attachmentImpl.getWeightInBytes());
    }
    return attachment;
  }

  private Template convertTemplateImplToTemplate(TemplateImpl templateImpl) throws Exception {
    Template template = null;
    if(templateImpl != null) {
      template = new Template();
      template.setDescription(templateImpl.getDescription());
      // TODO to complete
    }
    return template;
  }
}
