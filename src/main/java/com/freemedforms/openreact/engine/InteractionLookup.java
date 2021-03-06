/***************************************************************************
 *  The FreeMedForms project is a set of free, open source medical         *
 *  applications.                                                          *
 *  (C) 2008-2011 by Eric MAEKER, MD (France) <eric.maeker@free.fr>        *
 *  All rights reserved.                                                   *
 *                                                                         *
 *  This program is free software: you can redistribute it and/or modify   *
 *  it under the terms of the GNU General Public License as published by   *
 *  the Free Software Foundation, either version 3 of the License, or      *
 *  (at your option) any later version.                                    *
 *                                                                         *
 *  This program is distributed in the hope that it will be useful,        *
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of         *
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the          *
 *  GNU General Public License for more details.                           *
 *                                                                         *
 *  You should have received a copy of the GNU General Public License      *
 *  along with this program (COPYING.FREEMEDFORMS file).                   *
 *  If not, see <http://www.gnu.org/licenses/>.                            *
 ***************************************************************************/
/***************************************************************************
 *   OpenReact Web Portal                                                  *
 *   Main Developer : Jeff Buchbinder <jeff@freemedsoftware.org>           *
 *   Contributors :                                                        *
 *       NAME <MAIL@ADDRESS>                                               *
 ***************************************************************************/

package com.freemedforms.openreact.engine;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.log4j.Logger;

import com.freemedforms.openreact.db.DbUtil;
import com.freemedforms.openreact.types.CodeSet;
import com.freemedforms.openreact.types.Drug;
import com.freemedforms.openreact.types.DrugInteraction;
import com.freemedforms.openreact.types.InteractionType;

public class InteractionLookup {

	static final Logger log = Logger.getLogger(InteractionLookup.class);

	public static int LOOKUP_LIMIT = 20;

	/**
	 * Query to lookup ATC classes for a drug id.
	 */
	public static String Q_ATC_LOOKUP = "SELECT "
			+ " IAM_TREE.ID_CLASS AS ID_CLASS, LABELS.LABEL AS LABEL FROM IAM_TREE "
			+ " JOIN ATC_LABELS ON ATC_LABELS.ATC_ID=IAM_TREE.ID_CLASS "
			+ " JOIN LABELS_LINK ON LABELS_LINK.MASTER_LID=ATC_LABELS.MASTER_LID "
			+ " JOIN LABELS ON LABELS_LINK.LID=LABELS.LID "
			+ " WHERE (IAM_TREE.ID_ATC IN ( "
			+ " SELECT LK_MOL_ATC.ATC_ID FROM DRUGS "
			+ " JOIN COMPOSITION ON DRUGS.DID=COMPOSITION.DID "
			+ " JOIN MOLS ON COMPOSITION.MID=MOLS.MID "
			+ " JOIN LK_MOL_ATC ON MOLS.MID=LK_MOL_ATC.MID "
			+ " WHERE DRUGS.DID=? ) ) AND LABELS.LANG=?;";

	/**
	 * Query to find interactions from ATC codes.
	 */
	public static String Q_ATC_INTERACTIONS = "SELECT "
			+ " I.IAID AS ID, "
			+ " I.ATC_ID1 AS ATC_ID1, "
			+ " I.ATC_ID2 AS ATC_ID2, "
			+ " IK.WWW AS WWW, "
			+ " IK.TYPE AS TYPE, "
			+ " RL.LABEL AS RISK "
			+ " FROM INTERACTIONS I "
			+ " JOIN IA_IAK IAK ON IAK.IAID=I.IAID "
			+ " JOIN IAKNOWLEDGE IK ON IK.IAKID=IAK.IAKID "
			+ " JOIN LABELS_LINK RLL ON RLL.MASTER_LID=IK.RISK_MASTER_LID "
			+ " JOIN LABELS RL ON RLL.LID=RL.LID "
			+ " WHERE ( FIND_IN_SET(I.ATC_ID1, ?) AND FIND_IN_SET(I.ATC_ID2, ?) ) "
			+ " AND RL.LANG = ?;";

	/**
	 * Get ATC classes for a drug code.
	 * 
	 * @param drugId
	 * @return
	 */
	public static List<Integer> findAtcFromDrug(CodeSet codeset, Long drugId) {
		log.info("findAtcFromDrug(" + codeset + ", " + drugId + ")");
		List<Integer> result = new ArrayList<Integer>();
		Connection c = Configuration.getConnection();

		log.info(Q_ATC_LOOKUP);

		PreparedStatement q = null;
		try {
			q = c.prepareStatement(Q_ATC_LOOKUP);
			q.setLong(1, drugId);
			q.setString(2, codeset.getLang().substring(0, 2));
		} catch (SQLException e) {
			log.error(e);
			DbUtil.closeSafely(q);
			DbUtil.closeSafely(c);
			return result;
		}

		ResultSet rs = null;
		try {
			rs = q.executeQuery();
		} catch (SQLException e) {
			log.error(e);
			DbUtil.closeSafely(rs);
			DbUtil.closeSafely(q);
			DbUtil.closeSafely(c);
			return result;
		}

		log.info("Attempt to extract data from set");
		try {
			while (rs.next()) {
				log.info("ATC_ID = " + rs.getInt("ID_CLASS"));
				result.add(rs.getInt("ID_CLASS"));
			}
		} catch (SQLException e) {
			log.error(e);
			DbUtil.closeSafely(rs);
			DbUtil.closeSafely(q);
			DbUtil.closeSafely(c);
			return result;
		}

		DbUtil.closeSafely(rs);
		DbUtil.closeSafely(q);
		DbUtil.closeSafely(c);
		return result;
	}

	/**
	 * Given a list of DRUG ids, resolve interactions.
	 * 
	 * @param codeset
	 * @param drugIds
	 * @return
	 */
	public static List<DrugInteraction> findInteractionsFromDrugs(
			CodeSet codeset, List<Long> drugIds) {
		log.info("findInteractionsFromDrugs(" + codeset + ", "
				+ (drugIds != null ? drugIds.size() : "0") + ")");
		List<DrugInteraction> result = new ArrayList<DrugInteraction>();

		// Create a mapping of all drug ids -> ATC codes
		Map<Integer, List<Long>> mapAtcToDrugs = new HashMap<Integer, List<Long>>();
		for (Long drugId : drugIds) {
			log.info("Processing drug id " + drugId);
			List<Integer> atcCodesForDrug = findAtcFromDrug(codeset, drugId);
			log.info("Found " + atcCodesForDrug.size()
					+ " ATC classes for drugId " + drugId);
			for (Integer atcCodeForDrug : atcCodesForDrug) {
				log.info("Add ATC code " + atcCodeForDrug + " for drugId "
						+ drugId);
				if (mapAtcToDrugs.containsKey(atcCodeForDrug)) {
					// If it contains an array already, append
					mapAtcToDrugs.get(atcCodeForDrug).add(drugId);
				} else {
					// Create
					List<Long> newList = new ArrayList<Long>();
					newList.add(drugId);
					mapAtcToDrugs.put(atcCodeForDrug, newList);
				}
			}
		}

		List<DrugInteraction> atcInteractions = findInteractionsFromAtc(
				codeset, mapAtcToDrugs.keySet());

		// Keep cache of drugs so we only look each one up once.
		Map<Long, Drug> drugCache = new HashMap<Long, Drug>();

		List<String> storedInteractions = new ArrayList<String>();

		for (DrugInteraction atcInteraction : atcInteractions) {
			log.info("Interaction " + atcInteraction);

			// Get the actual ATC codes which were found in the interaction
			Integer atc1 = Integer.parseInt(atcInteraction.getAtc1());
			Integer atc2 = Integer.parseInt(atcInteraction.getAtc2());

			if (atc1 == atc2) {
				log.info("Skipping self-referential interaction for " + atc1);
				continue;
			}

			// Resolve these to the list of possible drugs
			List<Long> atc1list = mapAtcToDrugs.get(atc1);
			List<Long> atc2list = mapAtcToDrugs.get(atc2);

			// More or less recurse through and populate
			for (Long atc1loop : atc1list) {
				if (!drugCache.containsKey(atc1loop)) {
					log.info("Cache drugId " + atc1loop + " in ATC1 loop");
					Drug d = DrugLookup.getDrugById(atc1loop);
					d.setCodeSet(codeset);
					drugCache.put(atc1loop, d);
				}
				for (Long atc2loop : atc2list) {
					if (!drugCache.containsKey(atc2loop)) {
						log.info("Cache drugId " + atc2loop + " in ATC2 loop");
						Drug d = DrugLookup.getDrugById(atc2loop);
						d.setCodeSet(codeset);
						drugCache.put(atc2loop, d);
					}

					// Figure out which ones to skip
					if (atc1loop == atc2loop) {
						log
								.info("Should skip self-referential interaction for drugId "
										+ atc1loop);
						continue;
					}

					DrugInteraction ni = new DrugInteraction();

					// Copy basic stuff from the object we're working with
					ni.setAtc1(atcInteraction.getAtc1());
					ni.setAtc2(atcInteraction.getAtc2());
					ni.setId(atcInteraction.getId());
					ni.setLevel(atcInteraction.getLevel());
					ni.setWebLink(atcInteraction.getWebLink());
					ni.setRisk(atcInteraction.getRisk());

					// ... and now drop the drugs in
					ni.setDrug1(drugCache.get(atc1loop));
					ni.setDrug2(drugCache.get(atc2loop));

					// ... and push it into the result list if unique
					String key = atcInteraction.getAtc1() + "-"
							+ atcInteraction.getAtc2();
					if (!storedInteractions.contains(key)) {
						log.info("Adding interaction for " + key);
						storedInteractions.add(key);
						result.add(ni);
					}
				}
			}
		}

		return result;
	}

	/**
	 * Get a list of potential drug interactions from a list of ATC ID codes.
	 * 
	 * @param atcIds
	 * @return
	 */
	public static List<DrugInteraction> findInteractionsFromAtc(
			CodeSet codeset, Collection<Integer> atcIds) {
		List<DrugInteraction> result = new ArrayList<DrugInteraction>();
		Connection c = Configuration.getConnection();

		String findList = createSetForFind(atcIds);

		PreparedStatement q = null;
		try {
			q = c.prepareStatement(Q_ATC_INTERACTIONS);
			log.info(Q_ATC_INTERACTIONS);
			log.info(findList + " / "
					+ codeset.getLang().toLowerCase().substring(0, 2));
			q.setString(1, findList);
			q.setString(2, findList);
			q.setString(3, codeset.getLang().toLowerCase().substring(0, 2));
		} catch (SQLException e) {
			log.error(e);
			DbUtil.closeSafely(q);
			DbUtil.closeSafely(c);
			return result;
		}

		ResultSet rs = null;
		try {
			rs = q.executeQuery();
		} catch (SQLException e) {
			log.error(e);
			DbUtil.closeSafely(rs);
			DbUtil.closeSafely(q);
			DbUtil.closeSafely(c);
			return result;
		}
		try {
			while (rs.next()) {
				DrugInteraction di = new DrugInteraction();
				di.setId(rs.getInt("ID"));
				di.setAtc1(rs.getString("ATC_ID1"));
				di.setAtc2(rs.getString("ATC_ID2"));
				di.setWebLink(rs.getString("WWW"));
				di.setRisk(rs.getString("RISK"));
				di.setLevel(InteractionType.getByValue(rs.getString("TYPE")));
				// NOTE: Do not set drug1 and drug2, those would need to be
				// resolved from the matching ATC codes, which should not be
				// done in this function.
				result.add(di);
			}
		} catch (SQLException e) {
			log.error(e);
			DbUtil.closeSafely(rs);
			DbUtil.closeSafely(q);
			DbUtil.closeSafely(c);
			return result;
		}

		DbUtil.closeSafely(rs);
		DbUtil.closeSafely(q);
		DbUtil.closeSafely(c);
		return result;
	}

	/**
	 * Create a string usable for a FIND_IN_SET clause from a <List<Integer>>.
	 * 
	 * @param iS
	 * @return
	 */
	protected static String createSetForFind(Collection<Integer> iS) {
		StringBuilder sb = new StringBuilder();
		for (Integer i : iS) {
			if (sb.length() > 1) {
				sb.append(',');
			}
			sb.append(i.toString());
		}
		return sb.toString();
	}

}
