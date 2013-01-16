// --- BEGIN COPYRIGHT BLOCK ---
// This program is free software; you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published by
// the Free Software Foundation; version 2 of the License.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
// GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public License along
// with this program; if not, write to the Free Software Foundation, Inc.,
// 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA.
//
// (C) 2007 Red Hat, Inc.
// All rights reserved.
// --- END COPYRIGHT BLOCK ---
package netscape.security.pkcs;

import java.io.IOException;
import java.io.PrintStream;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.Signature;
import java.security.SignatureException;
import java.security.cert.CertificateException;

import com.netscape.cmsutil.util.Utils;

import netscape.security.util.BigInt;
import netscape.security.util.DerInputStream;
import netscape.security.util.DerOutputStream;
import netscape.security.util.DerValue;
import netscape.security.x509.AlgorithmId;
import netscape.security.x509.X500Name;
import netscape.security.x509.X500Signer;
import netscape.security.x509.X509Key;

/**
 * PKCS #10 certificate requests are created and sent to Certificate
 * Authorities, which then create X.509 certificates and return them to
 * the entity which created the certificate request. These cert requests
 * basically consist of the subject's X.500 name and public key, signed
 * using the corresponding private key.
 *
 * The ASN.1 syntax for a Certification Request is:
 *
 * <pre>
 * CertificationRequest ::= SEQUENCE {
 *    certificationRequestInfo CertificationRequestInfo,
 *    signatureAlgorithm       SignatureAlgorithmIdentifier,
 *    signature                Signature
 *  }
 *
 * SignatureAlgorithmIdentifier ::= AlgorithmIdentifier
 * Signature ::= BIT STRING
 *
 * CertificationRequestInfo ::= SEQUENCE {
 *    version                 Version,
 *    subject                 Name,
 *    subjectPublicKeyInfo    SubjectPublicKeyInfo,
 *    attributes [0] IMPLICIT Attributes
 * }
 * Attributes ::= SET OF Attribute
 * </pre>
 *
 * @author David Brownell
 * @author Amit Kapoor
 * @author Hemma Prafullchandra
 * @version 1.28
 */
public class PKCS10 {
    /**
     * Constructs an unsigned PKCS #10 certificate request. Before this
     * request may be used, it must be encoded and signed. Then it
     * must be retrieved in some conventional format (e.g. string).
     *
     * @param publicKey the public key that should be placed
     *            into the certificate generated by the CA.
     */
    public PKCS10(X509Key publicKey) {
        subjectPublicKeyInfo = publicKey;
        attributeSet = new PKCS10Attributes();
    }

    /**
     * Constructs an unsigned PKCS #10 certificate request. Before this
     * request may be used, it must be encoded and signed. Then it
     * must be retrieved in some conventional format (e.g. string).
     *
     * @param publicKey the public key that should be placed
     *            into the certificate generated by the CA.
     * @param attributes additonal set of PKCS10 attributes requested
     *            for in the certificate.
     */
    public PKCS10(X509Key publicKey, PKCS10Attributes attributes) {
        subjectPublicKeyInfo = publicKey;
        if (attributes != null)
            attributeSet = attributes;
        else
            attributeSet = new PKCS10Attributes();
    }

    /**
     * Parses an encoded, signed PKCS #10 certificate request, verifying
     * the request's signature as it does so. This constructor would
     * typically be used by a Certificate Authority, from which a new
     * certificate would then be constructed.
     *
     * @param data the DER-encoded PKCS #10 request.
     * @param sigver boolean specifies signature verification enabled or not
     * @exception IOException for low level errors reading the data
     * @exception SignatureException when the signature is invalid
     * @exception NoSuchAlgorithmException when the signature
     *                algorithm is not supported in this environment
     */
    public PKCS10(byte data[], boolean sigver)
            throws IOException, SignatureException, NoSuchAlgorithmException, java.security.NoSuchProviderException {
        DerInputStream in;
        DerValue seq[];
        AlgorithmId id;
        byte sigData[];
        Signature sig;

        certificateRequest = data;

        //
        // Outer sequence:  request, signature algorithm, signature.
        // Parse, and prepare to verify later.
        //
        in = new DerInputStream(data);
        seq = in.getSequence(3);

        if (seq.length != 3)
            throw new IllegalArgumentException("not a PKCS #10 request");

        data = seq[0].toByteArray(); // reusing this variable
        certRequestInfo = seq[0].toByteArray(); // make a copy
        id = AlgorithmId.parse(seq[1]);
        sigData = seq[2].getBitString();

        //
        // Inner sequence:  version, name, key, attributes
        //
        @SuppressWarnings("unused")
        BigInt serial = seq[0].data.getInteger(); // consume serial

        /*
        	if (serial.toInt () != 0)
        	    throw new IllegalArgumentException ("not PKCS #10 v1");
        */

        subject = new X500Name(seq[0].data);

        byte val1[] = seq[0].data.getDerValue().toByteArray();
        subjectPublicKeyInfo = X509Key.parse(new DerValue(val1));
        PublicKey publicKey = X509Key.parsePublicKey(new DerValue(val1));
        if (publicKey == null) {
            System.out.println("PKCS10: publicKey null");
            throw new SignatureException ("publicKey null");
        }

        // Cope with a somewhat common illegal PKCS #10 format
        if (seq[0].data.available() != 0)
            attributeSet = new PKCS10Attributes(seq[0].data);
        else
            attributeSet = new PKCS10Attributes();

        //
        // OK, we parsed it all ... validate the signature using the
        // key and signature algorithm we found.
        // temporary commented out
        try {
            String idName = id.getName();
            if (idName.equals("MD5withRSA"))
                idName = "MD5/RSA";
            else if (idName.equals("MD2withRSA"))
                idName = "MD2/RSA";
            else if (idName.equals("SHA1withRSA"))
                idName = "SHA1/RSA";
            else if (idName.equals("SHA1withDSA"))
                idName = "SHA1/DSA";
            else if (idName.equals("SHA1withEC"))
                idName = "SHA1/EC";
            else if (idName.equals("SHA256withEC"))
                idName = "SHA256/EC";
            else if (idName.equals("SHA384withEC"))
                idName = "SHA384/EC";
            else if (idName.equals("SHA512withEC"))
                idName = "SHA512/EC";

            if (sigver) {
                sig = Signature.getInstance(idName, "Mozilla-JSS");

                sig.initVerify(publicKey);
                sig.update(data);
                if (!sig.verify(sigData)) {
                System.out.println("PKCS10: sig.verify() failed");
                    throw new SignatureException("Invalid PKCS #10 signature");
                }
            }
        } catch (InvalidKeyException e) {
            System.out.println("PKCS10: "+ e.toString());
            throw new SignatureException("invalid key");
        }
    }

    public PKCS10(byte data[])
            throws IOException, SignatureException, NoSuchAlgorithmException, java.security.NoSuchProviderException {
        this(data, true);
    }

    /**
     * Create the signed certificate request. This will later be
     * retrieved in either string or binary format.
     *
     * @param requester identifies the signer (by X.500 name)
     *            and provides the private key used to sign.
     * @exception IOException on errors.
     * @exception CertificateException on certificate handling errors.
     * @exception SignatureException on signature handling errors.
     */
    public void encodeAndSign(X500Signer requester)
            throws CertificateException, IOException, SignatureException {
        DerOutputStream out, scratch;
        byte certificateRequestInfo[];
        byte sig[];

        if (certificateRequest != null)
            throw new SignatureException("request is already signed");

        subject = requester.getSigner();

        /*
         * Encode cert request info, wrap in a sequence for signing
         */
        scratch = new DerOutputStream();
        scratch.putInteger(new BigInt(0)); // version zero
        subject.encode(scratch); // X.500 name
        subjectPublicKeyInfo.encode(scratch); // public key
        attributeSet.encode(scratch);

        out = new DerOutputStream();
        out.write(DerValue.tag_Sequence, scratch); // wrap it!
        certificateRequestInfo = out.toByteArray();
        scratch = out;

        /*
         * Sign it ...
         */
        requester.update(certificateRequestInfo, 0,
                certificateRequestInfo.length);
        sig = requester.sign();

        /*
         * Build guts of SIGNED macro
         */
        requester.getAlgorithmId().encode(scratch); // sig algorithm
        scratch.putBitString(sig); // sig

        /*
         * Wrap those guts in a sequence
         */
        out = new DerOutputStream();
        out.write(DerValue.tag_Sequence, scratch);
        certificateRequest = out.toByteArray();
    }

    /**
     * Returns the subject's name.
     */
    public X500Name getSubjectName() {
        return subject;
    }

    /**
     * Returns the subject's public key.
     */
    public X509Key getSubjectPublicKeyInfo() {
        return subjectPublicKeyInfo;
    }

    /**
     * Returns the additional attributes requested.
     */
    public PKCS10Attributes getAttributes() {
        return attributeSet;
    }

    /**
     * Returns the encoded and signed certificate request as a
     * DER-encoded byte array.
     *
     * @return the certificate request, or null if encodeAndSign()
     *         has not yet been called.
     */
    public byte[] toByteArray() {
        return certificateRequest;
    }

    /**
     * Prints an E-Mailable version of the certificate request on the print
     * stream passed. The format is a common base64 encoded one, supported
     * by most Certificate Authorities because Netscape web servers have
     * used this for some time. Some certificate authorities expect some
     * more information, in particular contact information for the web
     * server administrator.
     *
     * @param out the print stream where the certificate request
     *            will be printed.
     * @exception IOException when an output operation failed
     * @exception SignatureException when the certificate request was
     *                not yet signed.
     */
    public void print(PrintStream out)
            throws IOException, SignatureException {
        if (certificateRequest == null)
            throw new SignatureException("Cert request was not signed");

        out.println("-----BEGIN NEW CERTIFICATE REQUEST-----");
        out.println(Utils.base64encode(certificateRequest));
        out.println("-----END NEW CERTIFICATE REQUEST-----");
    }

    /**
     * Provides a short description of this request.
     */
    public String toString() {
        return "[PKCS #10 certificate request:\n"
                + subjectPublicKeyInfo.toString()
                + " subject: <" + subject + ">" + "\n"
                + " attributes: " + attributeSet.toString()
                + "\n]";
    }

    /**
     * Retrieve the PKCS10 CertificateRequestInfo as a byte array
     */
    public byte[] getCertRequestInfo() {
        return certRequestInfo;
    }

    private X500Name subject;
    private X509Key subjectPublicKeyInfo;
    private PKCS10Attributes attributeSet;

    private byte certificateRequest[]; // signed
    private byte certRequestInfo[]; // inner content signed
}
