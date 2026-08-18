#!/bin/bash
# ============================================
# Mock OpenLDAP 初始化（osixia 镜像拉起后执行一次，幂等）
#   docker exec datanest-middleware-test-openldap /tmp/init-mock-openldap.sh
# 说明：
#   - osixia/openldap 镜像自带 memberof overlay（groupOfUniqueNames/uniqueMember），
#     且该 overlay 一经添加不可删除（OpenLDAP 限制）。
#   - 因此种子组使用 groupOfUniqueNames + uniqueMember，匹配自带 overlay。
#   - 导入顺序：先用户后组——组添加时用户已存在，memberOf 才由 overlay 立即回填。
# 种子用户：zhangsan(组 datanest-engineers) / lisi(组 datanest-admins)
# ============================================
set -e

echo "[mock-openldap] waiting for slapd to be ready..."
for i in $(seq 1 30); do
  if ldapsearch -x -H ldap://localhost -s base -b "" namingContexts >/dev/null 2>&1; then
    break
  fi
  sleep 1
done

# 先建 OU + 用户，再建组（组引用用户须已存在，memberof overlay 才会回填 memberOf）
ldapadd -x -H ldap://localhost -D "cn=admin,dc=example,dc=com" -w admin123 >/dev/null 2>&1 <<'EOF' || true
dn: ou=people,dc=example,dc=com
objectClass: organizationalUnit
ou: people

dn: uid=zhangsan,ou=people,dc=example,dc=com
objectClass: inetOrgPerson
objectClass: organizationalPerson
objectClass: person
objectClass: top
cn: Zhang San
sn: Zhang
uid: zhangsan
userPassword: Zhangsan@123
mail: zhangsan@example.com
displayName: 张三

dn: uid=lisi,ou=people,dc=example,dc=com
objectClass: inetOrgPerson
objectClass: organizationalPerson
objectClass: person
objectClass: top
cn: Li Si
sn: Li
uid: lisi
userPassword: Lisi@123
mail: lisi@example.com
displayName: 李四
EOF

ldapadd -x -H ldap://localhost -D "cn=admin,dc=example,dc=com" -w admin123 >/dev/null 2>&1 <<'EOF' || true
dn: ou=groups,dc=example,dc=com
objectClass: organizationalUnit
ou: groups

dn: cn=datanest-engineers,ou=groups,dc=example,dc=com
objectClass: groupOfUniqueNames
cn: datanest-engineers
uniqueMember: uid=zhangsan,ou=people,dc=example,dc=com

dn: cn=datanest-admins,ou=groups,dc=example,dc=com
objectClass: groupOfUniqueNames
cn: datanest-admins
uniqueMember: uid=lisi,ou=people,dc=example,dc=com
EOF

echo "[mock-openldap] initialization done"
