/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.wicket.page;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import javax.servlet.http.HttpSessionBindingEvent;
import javax.servlet.http.HttpSessionBindingListener;

import org.apache.wicket.pageStore.IPageStore;

/**
 * 
 */
public class PageStoreManager extends AbstractPageManager
{
	/**
	 * A cache that holds all registered page managers. <br/>
	 * applicationName -> page manager
	 */
	private static final ConcurrentMap<String, PageStoreManager> managers = new ConcurrentHashMap<String, PageStoreManager>();

	private final IPageStore pageStore;

	private final String applicationName;

	/**
	 * Construct.
	 * 
	 * @param applicationName
	 * @param pageStore
	 * @param context
	 */
	public PageStoreManager(final String applicationName, final IPageStore pageStore,
		final IPageManagerContext context)
	{
		super(context);

		this.applicationName = applicationName;
		this.pageStore = pageStore;

		if (managers.containsKey(applicationName))
		{
			throw new IllegalStateException("Manager for application with key '" + applicationName
				+ "' already exists.");
		}
		managers.put(applicationName, this);
	}

	/**
	 * Represents entry for single session. This is stored as session attribute and caches pages
	 * between requests.
	 * 
	 * @author Matej Knopp
	 */
	private static class SessionEntry implements Serializable, HttpSessionBindingListener
	{
		private static final long serialVersionUID = 1L;

		private final String applicationName;

		/**
		 * The id handed to the {@link IPageStore} to identify the session.
		 * <p>
		 * Note: If the container changes a session's id, this field remains unchanged on its
		 * initial value.
		 */
		private final String sessionId;

		private transient List<IManageablePage> sessionCache;
		private transient List<Object> afterReadObject;

		/**
		 * Construct.
		 * 
		 * @param applicationName
		 * @param sessionId
		 */
		public SessionEntry(String applicationName, String sessionId)
		{
			this.applicationName = applicationName;
			this.sessionId = sessionId;
		}

		/**
		 * 
		 * @return page store
		 */
		private IPageStore getPageStore()
		{
			PageStoreManager manager = managers.get(applicationName);

			if (manager == null)
			{
				return null;
			}

			return manager.pageStore;
		}

		/**
		 * 
		 * @param id
		 * @return null, if not found
		 */
		private IManageablePage findPage(int id)
		{
			for (IManageablePage p : sessionCache)
			{
				if (p.getPageId() == id)
				{
					return p;
				}
			}
			return null;
		}

		/**
		 * Add the page to cached pages if page with same id is not already there
		 * 
		 * @param page
		 */
		private void addPage(IManageablePage page)
		{
			if (page != null)
			{
				if (findPage(page.getPageId()) != null)
				{
					return;
				}

				sessionCache.add(page);
			}
		}

		/**
		 * If the pages are stored in temporary state (after deserialization) this method convert
		 * them to list of "real" pages
		 */
		private void convertAfterReadObjects()
		{
			if (sessionCache == null)
			{
				sessionCache = new ArrayList<IManageablePage>();
			}

			for (Object o : afterReadObject)
			{
				IManageablePage page = getPageStore().convertToPage(o);
				addPage(page);
			}

			afterReadObject = null;
		}

		/**
		 * 
		 * @param id
		 * @return manageable page
		 */
		public synchronized IManageablePage getPage(int id)
		{
			// check if pages are in deserialized state
			if (afterReadObject != null && afterReadObject.isEmpty() == false)
			{
				convertAfterReadObjects();
			}

			// try to find page with same id
			if (sessionCache != null)
			{
				IManageablePage page = findPage(id);
				if (page != null)
				{
					return page;
				}
			}

			// not found, ask pagestore for the page
			return getPageStore().getPage(sessionId, id);
		}

		/**
		 * set the list of pages to remember after the request
		 * 
		 * @param pages
		 */
		public synchronized void setSessionCache(final List<IManageablePage> pages)
		{
			sessionCache = new ArrayList<IManageablePage>(pages);
			afterReadObject = null;
		}

		/**
		 * Serializes all pages in this {@link SessionEntry}. If this is http worker thread then
		 * there is available {@link IPageStore} which will be asked to prepare the page for
		 * serialization (see DefaultPageStore$SerializePage). If there is no {@link IPageStore}
		 * available (session loading/persisting in application initialization/destruction thread)
		 * then the pages are serialized without any pre-processing
		 * 
		 * @param s
		 * @throws IOException
		 */
		private void writeObject(final ObjectOutputStream s) throws IOException
		{
			s.defaultWriteObject();

			// prepare for serialization and store the pages
			List<Serializable> serializedPages = new ArrayList<Serializable>();
			if (sessionCache != null)
			{
				IPageStore pageStore = getPageStore();
				for (IManageablePage p : sessionCache)
				{
					Serializable preparedPage;
					if (pageStore != null)
					{
						preparedPage = pageStore.prepareForSerialization(sessionId, p);
					}
					else
					{
						preparedPage = p;
					}

					if (preparedPage != null)
					{
						serializedPages.add(preparedPage);
					}
				}
			}
			s.writeObject(serializedPages);
		}

		/**
		 * Deserializes the pages in this {@link SessionEntry}. If this is http worker thread then
		 * there is available {@link IPageStore} which will be asked to restore the page from its
		 * optimized state (see DefaultPageStore$SerializePage). If there is no {@link IPageStore}
		 * available (session loading/persisting in application initialization/destruction thread)
		 * then the pages are deserialized without any post-processing
		 * 
		 * @param s
		 * @throws IOException
		 * @throws ClassNotFoundException
		 */
		@SuppressWarnings("unchecked")
		private void readObject(final ObjectInputStream s) throws IOException,
			ClassNotFoundException
		{
			s.defaultReadObject();

			afterReadObject = new ArrayList<Object>();

			List<Serializable> l = (List<Serializable>)s.readObject();

			// convert to temporary state after deserialization (will need to be processed
			// by convertAfterReadObject before the pages can be accessed)
			IPageStore pageStore = getPageStore();
			for (Serializable ser : l)
			{
				Object page;
				if (pageStore != null)
				{
					page = pageStore.restoreAfterSerialization(ser);
				}
				else
				{
					page = ser;
				}
				afterReadObject.add(page);
			}
		}

		@Override
		public void valueBound(HttpSessionBindingEvent event)
		{
		}

		@Override
		public void valueUnbound(HttpSessionBindingEvent event)
		{
			// WICKET-5164 use the original sessionId
			IPageStore store = getPageStore();
			// store might be null if destroyed already
			if (store != null)
			{
				store.unbind(sessionId);
			}
		}
	}

	/**
	 * {@link RequestAdapter} for {@link PageStoreManager}
	 * 
	 * @author Matej Knopp
	 */
	protected class PersistentRequestAdapter extends RequestAdapter
	{
		private static final String ATTRIBUTE_NAME = "wicket:persistentPageManagerData";

		private String getAttributeName()
		{
			return ATTRIBUTE_NAME + " - " + applicationName;
		}

		/**
		 * Construct.
		 * 
		 * @param context
		 */
		public PersistentRequestAdapter(IPageManagerContext context)
		{
			super(context);
		}

		/**
		 * @see org.apache.wicket.page.RequestAdapter#getPage(int)
		 */
		@Override
		protected IManageablePage getPage(int id)
		{
			IManageablePage touchedPage = findPage(id);
			if (touchedPage != null)
			{
				return touchedPage;
			}

			// try to get session entry for this session
			SessionEntry entry = getSessionEntry(false);

			if (entry != null)
			{
				return entry.getPage(id);
			}
			else
			{
				return null;
			}
		}

		/**
		 * 
		 * @param create
		 * @return Session Entry
		 */
		private SessionEntry getSessionEntry(boolean create)
		{
			SessionEntry entry = (SessionEntry)getSessionAttribute(getAttributeName());
			if (entry == null && create)
			{
				bind();
				entry = new SessionEntry(applicationName, getSessionId());
			}
			if (entry != null)
			{
				synchronized (entry)
				{
					setSessionAttribute(getAttributeName(), entry);
				}
			}
			return entry;
		}

		/**
		 * @see org.apache.wicket.page.RequestAdapter#newSessionCreated()
		 */
		@Override
		protected void newSessionCreated()
		{
			// if the session is not temporary bind a session entry to it
			if (getSessionId() != null)
			{
				getSessionEntry(true);
			}
		}

		/**
		 * @see org.apache.wicket.page.RequestAdapter#storeTouchedPages(java.util.List)
		 */
		@Override
		protected void storeTouchedPages(final List<IManageablePage> touchedPages)
		{
			if (!touchedPages.isEmpty())
			{
				SessionEntry entry = getSessionEntry(true);
				entry.setSessionCache(touchedPages);
				for (IManageablePage page : touchedPages)
				{
					// WICKET-5103 use the same sessionId as used in SessionEntry#getPage()
					pageStore.storePage(entry.sessionId, page);
				}
			}
		}
	}

	/**
	 * @see org.apache.wicket.page.AbstractPageManager#newRequestAdapter(org.apache.wicket.page.IPageManagerContext)
	 */
	@Override
	protected RequestAdapter newRequestAdapter(IPageManagerContext context)
	{
		return new PersistentRequestAdapter(context);
	}

	/**
	 * @see org.apache.wicket.page.AbstractPageManager#supportsVersioning()
	 */
	@Override
	public boolean supportsVersioning()
	{
		return true;
	}

	/**
	 * @see org.apache.wicket.page.AbstractPageManager#sessionExpired(java.lang.String)
	 */
	@Override
	public void sessionExpired(String sessionId)
	{
		// nothing to do, the SessionEntry will listen for it to become unbound by itself
	}

	/**
	 * @see org.apache.wicket.page.IPageManager#destroy()
	 */
	@Override
	public void destroy()
	{
		managers.remove(applicationName);
		pageStore.destroy();
	}
}
