/*
 * Copyright (C) 2003-2012 eXo Platform SAS.
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Affero General Public License
 * as published by the Free Software Foundation; either version 3
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, see<http://www.gnu.org/licenses/>.
 */
package org.exoplatform.wiki.mow.core.api.wiki;

import org.chromattic.api.annotations.PrimaryType;
import org.chromattic.api.annotations.Property;

@PrimaryType(name = WikiNodeType.WIKI_DRAFT_PAGE)
public abstract class DraftPageImpl extends PageImpl {
  @Property(name = WikiNodeType.Definition.DRAFT_TARGET_PAGE)
  public abstract String getTargetPage();
  public abstract void setTargetPage(String targetPage);
  
  @Property(name = WikiNodeType.Definition.DRAFT_TARGET_REVISION)
  public abstract String getTargetRevision();
  public abstract void setTargetRevision(String targetRevision);
  
  @Property(name = WikiNodeType.Definition.DRAFT_IS_NEW_PAGE)
  public abstract boolean isNewPage();
  public abstract void setNewPage(boolean isNewPage);
}
