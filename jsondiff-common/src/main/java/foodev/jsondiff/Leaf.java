package foodev.jsondiff;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.logging.Level;

import foodev.jsondiff.jsonwrap.JzonArray;
import foodev.jsondiff.jsonwrap.JzonElement;
import foodev.jsondiff.jsonwrap.JzonObject;
import foodev.jsondiff.jsonwrap.Wrapper;

class Leaf implements Comparable<Leaf> {

	Wrapper factory;

	Node parent;
	JzonElement val;
	Oper oper;

	List<Leaf> children = new LinkedList<Leaf>();
	List<Leaf> newStructure = new LinkedList<Leaf>();

	Leaf(Node parent, JzonElement val) {
		this.parent = parent;
		this.val = val;
	}

	void attach(Leaf leaf, Leaf at) {
		Leaf attach = this;
		int myIndex = (parent.parent == null) ? 0 : exactIndex(parent.parent.leaf.newStructure, this);
		int atIndex = (at == this) ? 0 : exactIndex(newStructure, at) + 1;
		while (leaf.oper != Oper.DELETE && attach.oper == Oper.DELETE && myIndex > 0) {
			myIndex--;
			attach = parent.parent.leaf.newStructure.get(myIndex);
			atIndex = attach.newStructure.size();
		}
		if (leaf.parent.parentHashCode == attach.parent.hashCode) {
			// direct attachment
			if (leaf.oper != Oper.DELETE && attach.oper == Oper.DELETE) {
				attach.parent.parent.leaf.attach(leaf, this);
			} else {
				leaf.parent.parent = attach.parent;
				if (leaf.oper == null) {
					leaf.oper = Oper.SET;
				}
				// int index = exactIndex(attach.newStructure, leaf);
				// if (index > -1) {
				// atIndex--;
				// attach.newStructure.remove(index);
				// }
				attach.newStructure.add(atIndex, leaf);
				if (JsonDiff.LOG.isLoggable(Level.FINEST))
					JsonDiff.LOG.finest("ATT " + leaf + " @" + this);
			}
		} else {
			parent.parent.leaf.attach(leaf, this);
		}
	}

	boolean cancelDelete(Leaf deleted, Leaf with) {
		if (deleted.parent.hashCode == with.parent.hashCode) {
			if (JsonDiff.LOG.isLoggable(Level.FINEST))
				JsonDiff.LOG.finest("SET " + deleted + " @" + with);
			with.newStructure.clear();
			deleted.oper = Oper.SET;
			deleted.val = with.val;
			Leaf newParent = deleted.parent.parent.leaf;
			deleted.parent.parent = newParent.parent;
			// recover deleted children (orphans)
			for (Leaf orphan : deleted.children) {
				orphan.parent.parent = deleted.parent;
				if (exactIndex(deleted.newStructure, orphan) == -1) {
					deleted.newStructure.add(orphan);
				}
			}
			deleted.rehash();
			return true;
		}
		return false;
	}

	void rehash() {
		parent.hashCode = parent.hashCode();
		for (Leaf child : newStructure) {
			child.rehash();
		}
	}

	Leaf checkCancelation(Leaf possibleCancellation) {
		for (Iterator<Leaf> iterator2 = newStructure.iterator(); iterator2.hasNext();) {
			Leaf check = iterator2.next();
			if (check != possibleCancellation) {
				if (check.oper != Oper.DELETE && !check.parent.getClass().equals(possibleCancellation.parent.getClass())) {
					// type node change: cancellation
					return check;
				}
				if (possibleCancellation.parent instanceof ObjNode && check.parent.hashCode == possibleCancellation.parent.hashCode) {
					return check;
				}
			}
		}
		return null;
	}

	void checkCancellations() {
		for (Iterator<Leaf> it = newStructure.iterator(); it.hasNext();) {
			Leaf child = it.next();
			if (child.oper == Oper.DELETE) {
				Leaf cancelled = checkCancelation(child);
				if (cancelled != null) {
					if (!cancelled.val.equals(child.val)) {
						cancelled.oper = Oper.SET;
					}
					it.remove();
				}
			}
		}
	}

	@Override
	public int compareTo(Leaf o) {
		return hashCode() - o.hashCode();
	}

	JsonDiff visitor;

	JzonArray createPatch(JzonObject patch) {
		JzonArray instructions = factory.createJsonArray();
		if (oper != Oper.DELETE) {
			checkCancellations();
			int i = 0, deletes = 0;
			for (Iterator<Leaf> it = newStructure.iterator(); it.hasNext();) {
				Leaf child = it.next();
				String key = child.parent.toString();
				String reIndexedKey = key;
				if (child.parent instanceof ArrNode) {
					((ArrNode) child.parent).index = i - deletes;
					reIndexedKey = child.parent.toString();
				}
				JzonObject insert = factory.createJsonObject();
				boolean deeper = true;
				if (child.oper == Oper.INSERT) {
					insert.add("+" + reIndexedKey, child.val);
					instructions.insert(instructions.size(), insert);
					deeper = false;
				} else if (child.oper == Oper.SET) {
					insert.add(reIndexedKey, child.val);
					instructions.insert(instructions.size(), insert);
					deeper = false;
				} else if (child.oper == Oper.DELETE) {
					insert.addProperty("-" + reIndexedKey, 0);
					instructions.insert(instructions.size(), insert);
					deeper = false;
				}
				if (deeper) {
					JzonObject childPatch = factory.createJsonObject();
					JzonArray childInstructions = child.createPatch(childPatch);
					if (childInstructions.size() > 0) {
						if (visitor != null && !child.val.isJsonPrimitive()
								&& !visitor.accept(child, childInstructions, childPatch)) {
							continue;
						}
						patch.add("~" + key, childInstructions);
					}
					if (!childPatch.entrySet().isEmpty()) {
						patch.add(key, childPatch);
					}
				}
				if (child.oper == Oper.DELETE) {
					deletes++;
				}
				i++;
			}
		} else {
			newStructure.clear();
		}
		return instructions;
	}

	void delete(Leaf leaf, Leaf at) {
		if (JsonDiff.LOG.isLoggable(Level.FINEST))
			JsonDiff.LOG.finest("DELETE  @" + this);
		leaf.oper = Oper.DELETE;
		if (leaf.isOrphan()) {
			Leaf attach = this;
			int atIndex = (at == this) ? 0 : exactIndex(newStructure, at) + 1;
			if (leaf.parent.parentHashCode == attach.parent.hashCode) {
				// direct attachment
				leaf.parent.parent = attach.parent;
				attach.newStructure.add(atIndex, leaf);
			} else {
				parent.parent.leaf.delete(leaf, this);
			}
		}
		for (Leaf orphan : leaf.newStructure) {
			orphan.parent.orphan();
		}
		leaf.newStructure.clear();
	}

	@Override
	public boolean equals(Object obj) {
		return hashCode() == ((Leaf) obj).hashCode();
	}

	@Override
	public int hashCode() {
		int i = parent.hashCode;
		if (val.isJsonArray()) {
			// for arr and obj we must hash in a type qualifier
			// since otherwise changes between these kinds of
			// nodes will be considered equal
			i = i * 31 + ArrNode.class.hashCode();
		} else if (val.isJsonObject()) {
			i = i * 31 + ObjNode.class.hashCode();
		} else {
			i = i * 31 + (val.isJsonPrimitive() || val.isJsonNull() ? val.hashCode() : 0);
		}
		return i;
	}

	void init() {
		this.parent.hashCode = this.parent.doHash(false);
		this.parent.parentHashCode = (this.parent.parent == null) ? 0 : this.parent.parent.doHash(false);
		this.newStructure.addAll(children);
	}

	void insert(Leaf leaf, Leaf where) {
		int hashCode = parent.hashCode;
		int insCode = leaf.parent.parent.hashCode;
		if (hashCode == 0 || insCode == hashCode) {
			if (JsonDiff.LOG.isLoggable(Level.FINEST))
				JsonDiff.LOG.finest("INSERT " + leaf + " @" + this);
			// eligible for insertion - check for sets after building the new graph
			leaf.oper = Oper.INSERT;
			leaf.parent.parent = parent;
			leaf.newStructure.clear();
			if (where != null) {
				int insertAt = exactIndex(newStructure, where) + 1;
				newStructure.add(insertAt, leaf);
			} else {
				// direct insertion
				newStructure.add(0, leaf);
			}
		} else {
			orphans(where);
			parent.parent.leaf.insert(leaf, this);
		}
	}

	boolean isOrphan() {
		return parent.hashCode != 0 && parent.isOrphan();
	}

	void orphans(Leaf where) {
		List<Leaf> orphans = null;
		int insertDeletionsIndex = 0;
		if ((where == null && !newStructure.isEmpty()) || newStructure.size() == 1) {
			orphans = newStructure;
		} else if (newStructure.size() > 1) {
			insertDeletionsIndex = exactIndex(newStructure, where) + 1;
			orphans = newStructure.subList(insertDeletionsIndex, newStructure.size());
		}
		if (orphans != null) {
			List<Leaf> newOrphans = new ArrayList<Leaf>();
			for (Leaf orphan : orphans) {
				orphan.parent.orphan();
				Node clone = orphan.parent.clone();
				Leaf leafClone = new Leaf(clone, orphan.val);
				leafClone.visitor = visitor;
				clone.leaf = leafClone;
				leafClone.oper = Oper.DELETE;
				newOrphans.add(leafClone);
			}
			orphans.clear();
			newStructure.addAll(insertDeletionsIndex, newOrphans);
		}
	}

	JzonObject patch() {
		checkCancellations();
		JzonObject patch = factory.createJsonObject();
		if (oper == Oper.INSERT) {
			patch.add("+" + parent.toString(), val);
		} else if (oper == Oper.SET) {
			patch.add(parent.toString(), val);
		} else if (oper == Oper.DELETE) {
			patch.add("-" + parent.toString(), val);
		} else {
			JzonArray childInstructions = createPatch(patch);
			if (childInstructions.size() > 0) {
				patch.add("~", childInstructions);
			}
		}
		return patch;
	}

	void print() {
		print(0);
	}

	void print(int tab) {
		for (Leaf lEntry : newStructure) {
			for (int i = 0; i < tab; i++) {
				System.out.print("\t");
			}
			System.out.println(lEntry);
			lEntry.print(tab + 1);
		}
	}

	protected static int exactIndex(Collection<Leaf> c, Leaf check) {
		int i = -1;
		for (Leaf l : c) {
			i++;
			if (l == check) {
				return i;
			}
		}
		return i;
	}

	void recover(List<Leaf> fromLeaves) {
		if (isOrphan()) {
			int thisIndex = exactIndex(fromLeaves, this);
			Leaf newParent = null;
			while (newParent == null || (oper != Oper.DELETE && newParent.oper == Oper.DELETE)) {
				thisIndex--;
				newParent = fromLeaves.get(thisIndex);
				if (newParent.isOrphan()) {
					newParent.recover(fromLeaves);
				}
			}
			while (newParent.parent.parent != null && newParent.parent.hashCode != parent.parentHashCode) {
				newParent = newParent.parent.parent.leaf;
				if (newParent.isOrphan()) {
					newParent.recover(fromLeaves);
				}
			}
			if (JsonDiff.LOG.isLoggable(Level.FINEST))
				JsonDiff.LOG.finest("RECOVER " + this + " @" + newParent);
			newParent.attach(this, null);
		}
	}

	@Override
	public String toString() {

		StringBuilder bld = new StringBuilder(newStructure.size() + "->");

		bld.append("LEAF");
		if (parent != null && parent instanceof ArrNode) {
			bld.append(parent.toString());
		}
		bld.append("<");
		if (oper != null) {
			bld.append(oper);
			bld.append("_");
		}
		if (parent != null && parent instanceof ObjNode) {
			bld.append(parent.toString());
			bld.append(":");
			bld.append(val);
		} else if (val.isJsonPrimitive() || val.isJsonNull()) {
			bld.append("{");
			bld.append(val);
			bld.append("}");
		}
		bld.append("_");
		bld.append(hashCode());
		bld.append(">");
		bld.append("\n");

		return bld.toString();

	}

}